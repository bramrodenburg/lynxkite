package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.controllers.Operation
import com.lynxanalytics.biggraph.controllers.ProjectTransformation
import com.lynxanalytics.biggraph.graph_util.Scripting._
import com.lynxanalytics.biggraph.controllers._

class AttributePropagationOperations(env: SparkFreeEnvironment) extends ProjectOperations(env) {
  import Operation.Implicits._

  val category = Categories.AttributePropagationOperations

  import com.lynxanalytics.biggraph.controllers.OperationParams._

  register("Aggregate to segmentation")(new ProjectTransformation(_) with SegOp {
    def addSegmentationParameters = params ++= aggregateParams(parent.vertexAttributes)
    def enabled = project.assertSegmentation
    def apply() = {
      for ((attr, choice, name) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo,
          AttributeWithLocalAggregator(parent.vertexAttributes(attr), choice))
        project.newVertexAttribute(name, result)
      }
    }
    override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
  })

  register(
    "Weighted aggregate to segmentation")(new ProjectTransformation(_) with SegOp {
      def addSegmentationParameters = {
        params += Choice("weight", "Weight", options = project.parentVertexAttrList[Double])
        params ++= aggregateParams(parent.vertexAttributes, weighted = true)
      }
      def enabled =
        project.assertSegmentation &&
          FEStatus.assert(
            parent.vertexAttributeNames[Double].nonEmpty,
            "No numeric vertex attributes on parent")
      def apply() = {
        val weightName = params("weight")
        val weight = parent.vertexAttributes(weightName).runtimeSafeCast[Double]
        for ((attr, choice, name) <- parseAggregateParams(params, weight = weightName)) {
          val result = aggregateViaConnection(
            seg.belongsTo,
            AttributeWithWeightedAggregator(weight, parent.vertexAttributes(attr), choice))
          project.newVertexAttribute(name, result)
        }
      }
      override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
    })

  register("Aggregate from segmentation")(new ProjectTransformation(_) with SegOp {
    def addSegmentationParameters = {
      params += Param(
        "prefix", "Generated name prefix", defaultValue = project.asSegmentation.segmentationName)
      params ++= aggregateParams(project.vertexAttributes)
    }
    def enabled = project.assertSegmentation
    def apply() = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice, name) <- parseAggregateParams(params, prefix = prefix)) {
        val result = aggregateViaConnection(
          seg.belongsTo.reverse,
          AttributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        seg.parent.newVertexAttribute(name, result)
      }
    }
    override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
  })

  register(
    "Weighted aggregate from segmentation")(new ProjectTransformation(_) with SegOp {
      def addSegmentationParameters = params ++= List(
        Param("prefix", "Generated name prefix",
          defaultValue = project.asSegmentation.segmentationName),
        Choice("weight", "Weight", options = project.vertexAttrList[Double])) ++
        aggregateParams(project.vertexAttributes, weighted = true)
      def enabled =
        project.assertSegmentation &&
          FEStatus.assert(project.vertexAttrList[Double].nonEmpty, "No numeric vertex attributes")
      def apply() = {
        val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
        val weightName = params("weight")
        val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
        for ((attr, choice, name) <- parseAggregateParams(params, prefix = prefix, weight = weightName)) {
          val result = aggregateViaConnection(
            seg.belongsTo.reverse,
            AttributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
          seg.parent.newVertexAttribute(name, result)
        }
      }
      override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
    })

  register("Aggregate on neighbors")(new ProjectTransformation(_) {
    params ++= List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Choice("direction", "Aggregate on", options = Direction.options)) ++
      aggregateParams(project.vertexAttributes)
    def enabled = project.hasEdgeBundle
    def apply() = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = Direction(params("direction"), project.edgeBundle).edgeBundle
      for ((attr, choice, name) <- parseAggregateParams(params, prefix = prefix)) {
        val result = aggregateViaConnection(
          edges,
          AttributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        project.newVertexAttribute(name, result)
      }
    }
    override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
  })

  register("Weighted aggregate on neighbors")(new ProjectTransformation(_) {
    params ++= List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Choice("weight", "Weight", options = project.vertexAttrList[Double]),
      Choice("direction", "Aggregate on", options = Direction.options)) ++
      aggregateParams(project.vertexAttributes, weighted = true)
    def enabled =
      FEStatus.assert(project.vertexAttrList[Double].nonEmpty, "No numeric vertex attributes") &&
        project.hasEdgeBundle
    def apply() = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = Direction(params("direction"), project.edgeBundle).edgeBundle
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice, name) <- parseAggregateParams(params, prefix = prefix, weight = weightName)) {
        val result = aggregateViaConnection(
          edges,
          AttributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
        project.newVertexAttribute(name, result)
      }
    }
    override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
  })

  register("Aggregate edge attribute to vertices")(new ProjectTransformation(_) {
    params ++= List(
      Param("prefix", "Generated name prefix", defaultValue = "edge"),
      Choice("direction", "Aggregate on", options = Direction.attrOptions)) ++
      aggregateParams(project.edgeAttributes)
    def enabled = project.hasEdgeBundle
    def apply() = {
      val direction = Direction(params("direction"), project.edgeBundle)
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice, name) <- parseAggregateParams(params, prefix = prefix)) {
        val result = aggregateFromEdges(
          direction.edgeBundle,
          AttributeWithLocalAggregator(
            direction.pull(project.edgeAttributes(attr)),
            choice))
        project.newVertexAttribute(name, result)
      }
    }
    override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
  })

  register(
    "Weighted aggregate edge attribute to vertices")(new ProjectTransformation(_) {
      params ++= List(
        Param("prefix", "Generated name prefix", defaultValue = "edge"),
        Choice("weight", "Weight", options = project.edgeAttrList[Double]),
        Choice("direction", "Aggregate on", options = Direction.attrOptions)) ++
        aggregateParams(
          project.edgeAttributes,
          weighted = true)
      def enabled =
        FEStatus.assert(project.edgeAttrList[Double].nonEmpty, "No numeric edge attributes")
      def apply() = {
        val direction = Direction(params("direction"), project.edgeBundle)
        val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
        val weightName = params("weight")
        val weight = project.edgeAttributes(weightName).runtimeSafeCast[Double]
        for ((attr, choice, name) <- parseAggregateParams(params, prefix = prefix, weight = weightName)) {
          val result = aggregateFromEdges(
            direction.edgeBundle,
            AttributeWithWeightedAggregator(
              direction.pull(weight),
              direction.pull(project.edgeAttributes(attr)),
              choice))
          project.newVertexAttribute(name, result)
        }
      }
      override def cleanParametersImpl(params: Map[String, String]) = cleanAggregateParams(params)
    })

  register("Copy vertex attributes from segmentation")(new ProjectTransformation(_) with SegOp {
    def addSegmentationParameters =
      params += Param("prefix", "Attribute name prefix", defaultValue = seg.segmentationName)
    def enabled =
      project.assertSegmentation &&
        FEStatus.assert(parent.vertexSet != null, s"No vertices on $parent") &&
        FEStatus.assert(
          seg.belongsTo.properties.isFunction,
          s"Vertices in base project are not guaranteed to be contained in only one segment")
    def apply() = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- project.vertexAttributes.toMap) {
        parent.newVertexAttribute(
          prefix + name,
          attr.pullVia(seg.belongsTo))
      }
    }
  })

  register("Copy vertex attributes to segmentation")(new ProjectTransformation(_) with SegOp {
    def addSegmentationParameters =
      params += Param("prefix", "Attribute name prefix")
    def enabled =
      project.assertSegmentation &&
        project.hasVertexSet &&
        FEStatus.assert(
          seg.belongsTo.properties.isReversedFunction,
          "Segments are not guaranteed to contain only one vertex")
    def apply() = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- parent.vertexAttributes.toMap) {
        project.newVertexAttribute(
          prefix + name,
          attr.pullVia(seg.belongsTo.reverse))
      }
    }
  })
}
