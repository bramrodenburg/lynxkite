package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.graph_util.Scripting._
import com.lynxanalytics.biggraph.controllers._

class HiddenOperations(env: SparkFreeEnvironment) extends ProjectOperations(env) {
  import Operation.Implicits._

  val category = Categories.HiddenOperations

  import com.lynxanalytics.biggraph.controllers.OperationParams._

  register("Anchor", List(), List(), "anchor")(new DecoratorOperation(_) {
    params += Code("description", "Description", language = "plain_text")
    params += Param("icon", "Icon URL")
    params += ParametersParam("parameters", "Parameters")
  })

  register("Check cliques")(new ProjectTransformation(_) with SegOp {
    def addSegmentationParameters = {
      params += Param("selected", "Segment IDs to check", defaultValue = "<All>")
      params += Choice("bothdir", "Edges required in both directions", options = FEOption.bools)
    }
    def enabled = project.hasVertexSet
    def apply() = {
      val selected =
        if (params("selected") == "<All>") None
        else Some(splitParam("selected").map(_.toLong).toSet)
      val op = graph_operations.CheckClique(selected, params("bothdir").toBoolean)
      val result = op(op.es, parent.edgeBundle)(op.belongsTo, seg.belongsTo).result
      parent.scalars("invalid_cliques") = result.invalid
    }
  })

  registerProjectCreatingOp(
    "Create enhanced example graph")(new ProjectOutputOperation(_) {
      def enabled = FEStatus.enabled
      def apply() = {
        val g = graph_operations.EnhancedExampleGraph()().result
        project.vertexSet = g.vertices
        project.edgeBundle = g.edges
        for ((name, attr) <- g.vertexAttributes) {
          project.newVertexAttribute(name, attr)
        }
        project.newVertexAttribute("id", project.vertexSet.idAttribute)
        project.edgeAttributes = g.edgeAttributes.mapValues(_.entity)
      }
    })

  registerProjectCreatingOp("Use metagraph as graph")(new ProjectTransformation(_) {
    params +=
      Param("timestamp", "Current timestamp", defaultValue = graph_util.Timestamp.toString)
    def enabled =
      FEStatus.assert(user.isAdmin, "Requires administrator privileges")
    def apply() = {
      val t = params("timestamp")
      val mg = graph_operations.MetaGraph(t, Some(env)).result
      project.vertexSet = mg.vs
      project.newVertexAttribute("GUID", mg.vGUID)
      project.newVertexAttribute("kind", mg.vKind)
      project.newVertexAttribute("name", mg.vName)
      project.newVertexAttribute("progress", mg.vProgress)
      project.newVertexAttribute("id", project.vertexSet.idAttribute)
      project.edgeBundle = mg.es
      project.newEdgeAttribute("kind", mg.eKind)
      project.newEdgeAttribute("name", mg.eName)
    }
  })

  register("External computation", List("table"), List("table"), "superpowers")(
    new TableOutputOperation(_) with Triggerable {
      params += Param("label", "Label")
      params += Param("snapshot_prefix", "Snapshot prefix")
      override def summary = params("label")
      def enabled = FEStatus.enabled
      override def getOutputs() = {
        val snapshotName = params("snapshot_prefix") + tableInput("table").gUID.toString
        val snapshot = DirectoryEntry.fromName(snapshotName).asSnapshotFrame
        makeOutput(snapshot.getState.table)
      }
      override def trigger(wc: WorkspaceController, gdc: GraphDrawingController) =
        concurrent.Future.successful({})
    })
}
