package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.BigGraphEnvironment
import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.graph_util.Filename
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.graph_api.MetaGraphManager.StringAsUUID
import scala.reflect.runtime.universe.typeOf

class Operations(env: BigGraphEnvironment) extends OperationRepository(env) {
  val Param = FEOperationParameterMeta // Short alias.

  // Categories.
  import Operation.Category
  abstract class VertexOperation(p: Project)
    extends Operation(p, Category("Vertex operations", "blue"))
  abstract class EdgeOperation(p: Project)
    extends Operation(p, Category("Edge operations", "orange"))
  abstract class AttributeOperation(p: Project)
    extends Operation(p, Category("Attribute operations", "yellow"))
  abstract class CreateSegmentationOperation(p: Project)
    extends Operation(p, Category("Create segmentation", "green"))
  abstract class HiddenOperation(p: Project)
      extends Operation(p, Category("Hidden", "", visible = false)) {
    val description = ""
  }
  trait SegOp extends Operation {
    protected def seg = project.asSegmentation
    protected def parent = seg.parent
  }
  abstract class HiddenSegmentationOperation(p: Project)
    extends HiddenOperation(p) with SegOp
  abstract class SegmentationOperation(p: Project)
    extends Operation(
      p, Category("Segmentation operations", "yellow", visible = p.isSegmentation)) with SegOp
  abstract class SegmentationWorkflowOperation(p: Project)
    extends Operation(
      p, Category("Workflows on segmentation", "magenta", visible = p.isSegmentation)) with SegOp

  register(new VertexOperation(_) {
    val title = "Discard vertices"
    val description = ""
    def parameters = List()
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      project.vertexSet = null
    }
  })

  register(new EdgeOperation(_) {
    val title = "Discard edges"
    val description = ""
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      project.edgeBundle = null
    }
  })

  register(new VertexOperation(_) {
    val title = "New vertex set"
    val description = "Creates a new vertex set with no edges and no attributes."
    def parameters = List(
      Param("size", "Vertex set size"))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val vs = graph_operations.CreateVertexSet(params("size").toInt)().result.vs
      project.setVertexSet(vs, idAttr = "id")
    }
  })

  register(new EdgeOperation(_) {
    val title = "Create random edge bundle"
    val description =
      """Creates edges randomly, so that each vertex will have a degree uniformly
      chosen between 0 and 2 × the provided parameter."""
    def parameters = List(
      Param("degree", "Average degree", defaultValue = "10"),
      Param("seed", "Seed", defaultValue = "0"))
    def enabled = hasVertexSet && hasNoEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.FastRandomEdgeBundle(params("seed").toInt, params("degree").toInt)
      project.edgeBundle = op(op.vs, project.vertexSet).result.es
    }
  })

  register(new EdgeOperation(_) {
    val title = "Connect vertices on attribute"
    val description =
      "Creates edges between vertices that are equal in a chosen attribute."
    def parameters = List(
      Param("attr", "Attribute", options = vertexAttributes[String]))
    def enabled =
      (hasVertexSet && hasNoEdgeBundle
        && FEStatus.assert(vertexAttributes[String].nonEmpty, "No string vertex attributes."))
    private def applyOn[T](attr: VertexAttribute[T]) = {
      val op = graph_operations.EdgesFromAttributeMatches[T]()
      project.edgeBundle = op(op.attr, attr).result.edges
    }
    def apply(params: Map[String, String]) =
      applyOn(project.vertexAttributes(params("attr")))
  })

  val importHelpText =
    """ Wildcard (foo/*.csv) and glob (foo/{bar,baz}.csv) patterns are accepted. S3 paths must
      include the key name and secret key in the following format:
        <tt>s3n://key_name:secret_key@bucket/dir/file</tt>
      """

  register(new VertexOperation(_) {
    val title = "Import vertices"
    val description =
      """Imports vertices (no edges) from a CSV file, or files.
      Each field in the CSV will be accessible as a vertex attribute.
      An extra vertex attribute is generated to hold the internal vertex ID.
      """ + importHelpText
    def parameters = List(
      Param("files", "Files", kind = "file"),
      Param("header", "Header", defaultValue = "<read first line>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param("id-attr", "ID attribute name", defaultValue = "id"),
      Param("filter", "(optional) Filtering expression"))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val files = Filename(params("files"))
      val header = if (params("header") == "<read first line>")
        graph_operations.ImportUtil.header(files) else params("header")
      val csv = graph_operations.CSV(
        files,
        params("delimiter"),
        header,
        JavaScript(params("filter")))
      val imp = graph_operations.ImportVertexList(csv)().result
      project.vertexSet = imp.vertices
      project.vertexAttributes = imp.attrs.mapValues(_.entity)
      val idAttr = params("id-attr")
      assert(
        !project.vertexAttributes.contains(idAttr),
        s"The CSV also contains a field called '$idAttr'. Please pick a different name.")
      project.vertexAttributes(idAttr) = idAsAttribute(project.vertexSet)
    }
  })

  register(new EdgeOperation(_) {
    val title = "Import edges for existing vertices"
    val description =
      """Imports edges from a CSV file, or files. Your vertices must have a key attribute, by which
      the edges can be attached to them.""" + importHelpText
    def parameters = List(
      Param("files", "Files", kind = "file"),
      Param("header", "Header", defaultValue = "<read first line>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param("attr", "Vertex id attribute", options = vertexAttributes[String]),
      Param("src", "Source ID field"),
      Param("dst", "Destination ID field"),
      Param("filter", "(optional) Filtering expression"))
    def enabled =
      hasNoEdgeBundle &&
        hasVertexSet &&
        FEStatus.assert(vertexAttributes[String].nonEmpty, "No vertex attributes to use as id.")
    def apply(params: Map[String, String]) = {
      val files = Filename(params("files"))
      val header = if (params("header") == "<read first line>")
        graph_operations.ImportUtil.header(files) else params("header")
      val csv = graph_operations.CSV(
        files,
        params("delimiter"),
        header,
        JavaScript(params("filter")))
      val src = params("src")
      val dst = params("dst")
      val attr = project.vertexAttributes(params("attr")).runtimeSafeCast[String]
      val op = graph_operations.ImportEdgeListForExistingVertexSet(csv, src, dst)
      val imp = op(op.srcVidAttr, attr)(op.dstVidAttr, attr).result
      project.edgeBundle = imp.edges
      project.edgeAttributes = imp.attrs.mapValues(_.entity)
    }
  })

  register(new EdgeOperation(_) {
    val title = "Import vertices and edges from single CSV fileset"
    val description =
      """Imports edges from a CSV file, or files.
      Each field in the CSV will be accessible as an edge attribute.
      Vertices will be generated for the endpoints of the edges.
      Two vertex attributes will be generated.
      "stringID" will contain the ID string that was used in the CSV.
      "id" will contain the internal vertex ID.
      """ + importHelpText
    def parameters = List(
      Param("files", "Files", kind = "file"),
      Param("header", "Header", defaultValue = "<read first line>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param("src", "Source ID field"),
      Param("dst", "Destination ID field"),
      Param("filter", "(optional) Filtering expression"))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val files = Filename(params("files"))
      val header = if (params("header") == "<read first line>")
        graph_operations.ImportUtil.header(files) else params("header")
      val csv = graph_operations.CSV(
        files,
        params("delimiter"),
        header,
        JavaScript(params("filter")))
      val src = params("src")
      val dst = params("dst")
      val imp = graph_operations.ImportEdgeList(csv, src, dst)().result
      project.setVertexSet(imp.vertices, idAttr = "id")
      project.vertexAttributes("stringID") = imp.stringID
      project.edgeBundle = imp.edges
      project.edgeAttributes = imp.attrs.mapValues(_.entity)
    }
  })

  register(new AttributeOperation(_) {
    val title = "Import vertex attributes"
    val description =
      """Imports vertex attributes for existing vertices from a CSV file.
      """ + importHelpText
    def parameters = List(
      Param("files", "Files", kind = "file"),
      Param("header", "Header", defaultValue = "<read first line>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param("id-attr", "Vertex id attribute", options = vertexAttributes[String]),
      Param("id-field", "ID field in the CSV file"),
      Param("prefix", "Name prefix for the imported vertex attributes", defaultValue = ""))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      val files = Filename(params("files"))
      val header = if (params("header") == "<read first line>")
        graph_operations.ImportUtil.header(files) else params("header")
      val csv = graph_operations.CSV(
        files,
        params("delimiter"),
        header)
      val idAttr = project.vertexAttributes(params("id-attr")).runtimeSafeCast[String]
      val op = graph_operations.ImportAttributesForExistingVertexSet(csv, params("id-field"))
      val res = op(op.idAttr, idAttr).result
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- res.attrs) {
        project.vertexAttributes(prefix + name) = attr
      }
    }
  })

  register(new CreateSegmentationOperation(_) {
    val title = "Maximal cliques"
    val description = ""
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "maximal_cliques"),
      Param("bothdir", "Edges required in both directions", options = UIValue.list(List("true", "false"))),
      Param("min", "Minimum clique size", defaultValue = "3"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.FindMaxCliques(params("min").toInt, params("bothdir").toBoolean)
      val result = op(op.es, project.edgeBundle).result
      val segmentation = project.segmentation(params("name"))
      segmentation.project.setVertexSet(result.segments, idAttr = "id")
      segmentation.project.notes = title
      segmentation.belongsTo = result.belongsTo
    }
  })

  register(new HiddenSegmentationOperation(_) {
    val title = "Check cliques"
    def parameters = List(
      Param("selected", "Clique ids to check", defaultValue = "<All>"),
      Param("bothdir", "Edges required in both directions", options = UIValue.list(List("true", "false"))))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      val selected =
        if (params("selected") == "<All>") None
        else Some(params("selected").split(",").map(_.toLong).toSet)
      val op = graph_operations.CheckClique(selected, params("bothdir").toBoolean)
      val result = op(op.es, parent.edgeBundle)(op.belongsTo, seg.belongsTo).result
      parent.scalars("invalid_cliques") = result.invalid
    }
  })

  register(new CreateSegmentationOperation(_) {
    val title = "Connected components"
    val description = ""
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "connected_components"),
      Param(
        "type",
        "Connectedness type",
        options = UIValue.list(List("weak", "strong"))))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val symmetric = if (params("type") == "weak") {
        val areop = graph_operations.AddReversedEdges()
        areop(areop.es, project.edgeBundle).result.esPlus.entity
      } else {
        val rnseop = graph_operations.RemoveNonSymmetricEdges()
        rnseop(rnseop.es, project.edgeBundle).result.symmetric.entity
      }
      val op = graph_operations.ConnectedComponents()
      val result = op(op.es, symmetric).result
      val segmentation = project.segmentation(params("name"))
      segmentation.project.setVertexSet(result.segments, idAttr = "id")
      segmentation.project.notes = title
      segmentation.belongsTo = result.belongsTo
    }
  })

  register(new CreateSegmentationOperation(_) {
    val title = "Find infocom communities"
    val description = ""
    def parameters = List(
      Param(
        "cliques_name", "Name for maximal cliques segmentation", defaultValue = "maximal_cliques"),
      Param(
        "communities_name", "Name for communities segmentation", defaultValue = "communities"),
      Param("bothdir", "Edges required in cliques in both directions", defaultValue = "true"),
      Param("min_cliques", "Minimum clique size", defaultValue = "3"),
      Param("adjacency_threshold", "Adjacency threshold for clique overlaps", defaultValue = "0.6"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val cliquesResult = {
        val op = graph_operations.FindMaxCliques(
          params("min_cliques").toInt, params("bothdir").toBoolean)
        op(op.es, project.edgeBundle).result
      }

      val cliquesSegmentation = project.segmentation(params("cliques_name"))
      cliquesSegmentation.project.setVertexSet(cliquesResult.segments, idAttr = "id")
      cliquesSegmentation.project.notes = "Maximal cliques of %s".format(project.projectName)
      cliquesSegmentation.belongsTo = cliquesResult.belongsTo
      cliquesSegmentation.project.vertexAttributes("size") =
        computeSegmentSizes(cliquesSegmentation)

      val cedges = {
        val op = graph_operations.InfocomOverlapForCC(params("adjacency_threshold").toDouble)
        op(op.belongsTo, cliquesResult.belongsTo).result.overlaps
      }

      val ccResult = {
        val op = graph_operations.ConnectedComponents()
        op(op.es, cedges).result
      }

      val weightedVertexToClique = const(cliquesResult.belongsTo)
      val weightedCliqueToCommunity = const(ccResult.belongsTo)

      val vertexToCommunity = {
        val op = graph_operations.ConcatenateBundles()
        op(
          op.edgesAB, cliquesResult.belongsTo)(
            op.edgesBC, ccResult.belongsTo)(
              op.weightsAB, weightedVertexToClique)(
                op.weightsBC, weightedCliqueToCommunity).result.edgesAC
      }

      val communitiesSegmentation = project.segmentation(params("communities_name"))
      communitiesSegmentation.project.setVertexSet(ccResult.segments, idAttr = "id")
      communitiesSegmentation.project.notes =
        "Infocom Communities of %s".format(project.projectName)
      communitiesSegmentation.belongsTo = vertexToCommunity
      communitiesSegmentation.project.vertexAttributes("size") =
        computeSegmentSizes(communitiesSegmentation)
    }
  })

  register(new AttributeOperation(_) {
    val title = "Internal vertex ID as attribute"
    val description =
      """Exposes the internal vertex ID as an attribute. This attribute is automatically generated
      by operations that generate new vertex sets. But you can regenerate it with this operation
      if necessary."""
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "id"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      project.vertexAttributes(params("name")) = idAsAttribute(project.vertexSet)
    }
  })

  def idAsAttribute(vs: VertexSet) = {
    graph_operations.IdAsAttribute.run(vs)
  }

  register(new AttributeOperation(_) {
    val title = "Add gaussian vertex attribute"
    val description =
      "Generates a new random double attribute with a Gaussian distribution."
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "random"),
      Param("seed", "Seed", defaultValue = "0"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      val op = graph_operations.AddGaussianVertexAttribute(params("seed").toInt)
      project.vertexAttributes(params("name")) = op(op.vertices, project.vertexSet).result.attr
    }
  })

  register(new AttributeOperation(_) {
    val title = "Add constant edge attribute"
    val description = ""
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "weight"),
      Param("value", "Value", defaultValue = "1"),
      Param("type", "Type", options = UIValue.list(List("Double", "String"))))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val res = {
        if (params("type") == "Double") {
          const(project.edgeBundle, params("value").toDouble)
        } else {
          graph_operations.AddConstantAttribute.run(project.edgeBundle.asVertexSet, params("value"))
        }
      }
      project.edgeAttributes(params("name")) = res
    }
  })

  register(new AttributeOperation(_) {
    val title = "Add constant vertex attribute"
    val description = ""
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "weight"),
      Param("value", "Value", defaultValue = "1"),
      Param("type", "Type", options = UIValue.list(List("Double", "String"))))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = (params("type") == "Double"), params("value"))
      project.vertexAttributes(params("name")) = op(op.vs, project.vertexSet).result.attr
    }
  })

  register(new AttributeOperation(_) {
    val title = "Fill with constant default value"
    val description =
      """An attribute may not be defined on every vertex. This operation sets a default value
      for the vertices where it was not defined."""
    def parameters = List(
      Param("attr", "Vertex attribute", options = vertexAttributes[String] ++ vertexAttributes[Double]),
      Param("def", "Default value"))
    def enabled = FEStatus.assert(
      (vertexAttributes[String] ++ vertexAttributes[Double]).nonEmpty, "No vertex attributes.")
    def apply(params: Map[String, String]) = {
      val attr = project.vertexAttributes(params("attr"))
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = attr.is[Double], params("def"))
      val default = op(op.vs, project.vertexSet).result
      project.vertexAttributes(params("attr")) = unifyAttribute(attr, default.attr.entity)
    }
  })

  register(new EdgeOperation(_) {
    val title = "Reverse edge direction"
    val description = ""
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      project.edgeBundle = reverse(project.edgeBundle)
    }
  })

  register(new AttributeOperation(_) {
    val title = "Clustering coefficient"
    val description = ""
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "clustering_coefficient"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.ClusteringCoefficient()
      project.vertexAttributes(params("name")) = op(op.es, project.edgeBundle).result.clustering
    }
  })

  register(new AttributeOperation(_) {
    val title = "Embeddedness"
    val description = "Calculates the overlap size of vertex neighborhoods along the edges."
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "embeddedness"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.Embeddedness()
      project.edgeAttributes(params("name")) = op(op.es, project.edgeBundle).result.embeddedness
    }
  })

  register(new AttributeOperation(_) {
    val title = "Degree"
    val description = ""
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "degree"),
      Param("inout", "Type", options = UIValue.list(List("in", "out", "all", "symmetric"))))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val es = project.edgeBundle
      val esSym = {
        val op = graph_operations.RemoveNonSymmetricEdges()
        op(op.es, es).result.symmetric
      }
      val deg = params("inout") match {
        case "in" => applyOn(reverse(es))
        case "out" => applyOn(es)
        case "symmetric" => applyOn(esSym)
        case "all" => graph_operations.DeriveJS.add(applyOn(reverse(es)), applyOn(es))
      }
      project.vertexAttributes(params("name")) = deg
    }

    private def applyOn(es: EdgeBundle): VertexAttribute[Double] = {
      val op = graph_operations.OutDegree()
      op(op.es, es).result.outDegree
    }
  })

  register(new AttributeOperation(_) {
    val title = "PageRank"
    val description = ""
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "page_rank"),
      Param("weights", "Weight attribute", options = edgeAttributes[Double]),
      Param("iterations", "Number of iterations", defaultValue = "5"),
      Param("damping", "Damping factor", defaultValue = "0.85"))
    def enabled = FEStatus.assert(edgeAttributes[Double].nonEmpty, "No numeric edge attributes.")
    def apply(params: Map[String, String]) = {
      val op = graph_operations.PageRank(params("damping").toDouble, params("iterations").toInt)
      val weights = project.edgeAttributes(params("weights")).runtimeSafeCast[Double]
      project.vertexAttributes(params("name")) =
        op(op.es, project.edgeBundle)(op.weights, weights).result.pagerank
    }
  })

  register(new VertexOperation(_) {
    val title = "Example Graph"
    val description =
      "Creates small test graph with 4 people and 4 edges between them."
    def parameters = List()
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val g = graph_operations.ExampleGraph()().result
      project.vertexSet = g.vertices
      project.edgeBundle = g.edges
      project.vertexAttributes = g.vertexAttributes.mapValues(_.entity)
      project.vertexAttributes("id") = idAsAttribute(project.vertexSet)
      project.edgeAttributes = g.edgeAttributes.mapValues(_.entity)
    }
  })

  register(new AttributeOperation(_) {
    val title = "Vertex attribute to string"
    val description = ""
    def parameters = List(
      Param("attr", "Vertex attribute", options = vertexAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes.")
    private def applyOn[T](attr: VertexAttribute[T]) = {
      val op = graph_operations.VertexAttributeToString[T]()
      op(op.attr, attr).result.attr
    }
    def apply(params: Map[String, String]) = {
      for (attr <- params("attr").split(",")) {
        project.vertexAttributes(attr) = applyOn(project.vertexAttributes(attr))
      }
    }
  })

  register(new AttributeOperation(_) {
    val title = "Vertex attribute to double"
    val description = ""
    def parameters = List(
      Param("attr", "Vertex attribute", options = vertexAttributes[String], multipleChoice = true))
    def enabled = FEStatus.assert(vertexAttributes[String].nonEmpty, "No string vertex attributes.")
    def apply(params: Map[String, String]) = {
      for (name <- params("attr").split(",")) {
        val attr = project.vertexAttributes(name).runtimeSafeCast[String]
        project.vertexAttributes(name) = toDouble(attr)
      }
    }
  })

  register(new VertexOperation(_) {
    val title = "Edge Graph"
    val description =
      """Creates the dual graph, where each vertex corresponds to an edge in the current graph.
      The vertices will be connected, if one corresponding edge is the continuation of the other.
      """
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.EdgeGraph()
      val g = op(op.es, project.edgeBundle).result
      project.setVertexSet(g.newVS, idAttr = "id")
      project.edgeBundle = g.newES
    }
  })

  register(new AttributeOperation(_) {
    val title = "Derived vertex attribute"
    val description =
      """Generates a new attribute based on existing attributes. The value expression can be
      an arbitrary JavaScript expression, and it can refer to existing attributes as if they
      were local variables. For example you can write <tt>age * 2</tt> to generate a new attribute
      that is the double of the age attribute. Or you can write
      <tt>gender == 'Male' ? 'Mr ' + name : 'Ms ' + name</tt> for a more complex example.
      """
    def parameters = List(
      Param("output", "Save as"),
      Param("type", "Result type", options = UIValue.list(List("double", "string"))),
      Param("expr", "Value", defaultValue = "1"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      val expr = params("expr")
      var numAttrNames = List[String]()
      var numAttrs = List[VertexAttribute[Double]]()
      var strAttrNames = List[String]()
      var strAttrs = List[VertexAttribute[String]]()
      var vecAttrNames = List[String]()
      var vecAttrs = List[VertexAttribute[Vector[_]]]()
      project.vertexAttributes.foreach {
        case (name, attr) if expr.contains(name) && attr.is[Double] =>
          numAttrNames +:= name
          numAttrs +:= attr.runtimeSafeCast[Double]
        case (name, attr) if expr.contains(name) && attr.is[String] =>
          strAttrNames +:= name
          strAttrs +:= attr.runtimeSafeCast[String]
        case (name, attr) if expr.contains(name) && isVector(attr) =>
          implicit var tt = attr.typeTag
          vecAttrNames +:= name
          vecAttrs +:= vectorToAny(attr.asInstanceOf[VectorAttr[_]])
        case (name, attr) if expr.contains(name) =>
          log.warn(s"'$name' is of an unsupported type: ${attr.typeTag.tpe}")
        case _ => ()
      }
      val js = JavaScript(expr)
      // Figure out the return type.
      val op: graph_operations.DeriveJS[_] = params("type") match {
        case "string" =>
          graph_operations.DeriveJSString(js, numAttrNames, strAttrNames, vecAttrNames)
        case "double" =>
          graph_operations.DeriveJSDouble(js, numAttrNames, strAttrNames, vecAttrNames)
      }
      val result = op(
        op.vs, project.vertexSet)(
          op.numAttrs, numAttrs)(
            op.strAttrs, strAttrs)(
              op.vecAttrs, vecAttrs).result
      project.vertexAttributes(params("output")) = result.attr
    }

    def isVector[T](attr: VertexAttribute[T]): Boolean = {
      import scala.reflect.runtime.universe._
      // Vector is covariant, so Vector[X] <:< Vector[Any].
      return attr.typeTag.tpe <:< typeOf[Vector[Any]]
    }
    type VectorAttr[T] = VertexAttribute[Vector[T]]
    def vectorToAny[T](attr: VectorAttr[T]): VertexAttribute[Vector[Any]] = {
      val op = graph_operations.AttributeVectorToAny[T]()
      op(op.attr, attr).result.attr
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Aggregate to segmentation"
    val description = "For example, it can calculate the average age of each clique."
    def parameters = aggregateParams(parent.vertexAttributes)
    def enabled =
      FEStatus.assert(parent.vertexAttributes.nonEmpty,
        "No vertex attributes on parent")
    def apply(params: Map[String, String]) = {
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo,
          attributeWithLocalAggregator(parent.vertexAttributes(attr), choice))
        project.vertexAttributes(s"${attr}_${choice}") = result
      }
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Weighted aggregate to segmentation"
    val description =
      "For example, it can calculate the average age per kilogram of each clique."
    def parameters = List(
      Param("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(parent.vertexAttributes, weighted = true)
    def enabled =
      FEStatus.assert(parent.vertexAttributeNames[Double].nonEmpty,
        "No numeric vertex attributes on parent")
    def apply(params: Map[String, String]) = {
      val weightName = params("weight")
      val weight = parent.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo,
          attributeWithWeightedAggregator(weight, parent.vertexAttributes(attr), choice))
        project.vertexAttributes(s"${attr}_${choice}_by_${weightName}") = result
      }
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Aggregate from segmentation"
    val description =
      "For example, it can calculate the average size of cliques a person belongs to."
    def parameters = List(
      Param("prefix", "Generated name prefix",
        defaultValue = project.asSegmentation.name)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          reverse(seg.belongsTo),
          attributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        seg.parent.vertexAttributes(s"${prefix}${attr}_${choice}") = result
      }
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Weighted aggregate from segmentation"
    val description =
      """For example, it can calculate an averge over the cliques a person belongs to,
      weighted by the size of the cliques."""
    def parameters = List(
      Param("prefix", "Generated name prefix",
        defaultValue = project.asSegmentation.name),
      Param("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(project.vertexAttributes, weighted = true)
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          reverse(seg.belongsTo),
          attributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
        seg.parent.vertexAttributes(s"${prefix}${attr}_${choice}_by_${weightName}") = result
      }
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Create edges from set overlaps"
    val description = "Connects segments with large enough overlaps."
    def parameters = List(
      Param("minOverlap", "Minimal overlap for connecting two segments", defaultValue = "3"))
    def enabled = hasNoEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.SetOverlap(params("minOverlap").toInt)
      val res = op(op.belongsTo, seg.belongsTo).result
      project.edgeBundle = res.overlaps
      // We convert to Double as our stupid FE cannot deal with Ints too well. :)
      project.edgeAttributes("Overlap size") =
        graph_operations.VertexAttributeToDouble.run(
          graph_operations.VertexAttributeToString.run(res.overlapSize))
    }
  })

  register(new AttributeOperation(_) {
    val title = "Aggregate on neighbors"
    val description =
      "For example it can calculate the average age of the friends of each person."
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Param("direction", "Aggregate on",
        options = UIValue.list(List("incoming edges", "outgoing edges")))) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes") && hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = params("direction") match {
        case "incoming edges" => project.edgeBundle
        case "outgoing edges" => reverse(project.edgeBundle)
      }
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          edges,
          attributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        project.vertexAttributes(s"${prefix}${attr}_${choice}") = result
      }
    }
  })

  register(new AttributeOperation(_) {
    val title = "Weighted aggregate on neighbors"
    val description =
      "For example it can calculate the average age per kilogram of the friends of each person."
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Param("weight", "Weight", options = vertexAttributes[Double]),
      Param("direction", "Aggregate on",
        options = UIValue.list(List("incoming edges", "outgoing edges")))) ++
      aggregateParams(project.vertexAttributes, weighted = true)
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes") && hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = params("direction") match {
        case "incoming edges" => project.edgeBundle
        case "outgoing edges" => reverse(project.edgeBundle)
      }
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((name, choice) <- parseAggregateParams(params)) {
        val attr = project.vertexAttributes(name)
        val result = aggregateViaConnection(
          edges,
          attributeWithWeightedAggregator(weight, attr, choice))
        project.vertexAttributes(s"${prefix}${name}_${choice}_by_${weightName}") = result
      }
    }
  })

  register(new VertexOperation(_) {
    val title = "Merge vertices by attribute"
    val description =
      """Merges each set of vertices that are equal by the chosen attribute. Aggregations
      can be specified for how to handle the rest of the attributes, which may be different
      among the merged vertices."""
    def parameters = List(
      Param("key", "Match by", options = vertexAttributes)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def merge[T](attr: VertexAttribute[T]): graph_operations.MergeVertices.Output = {
      val op = graph_operations.MergeVertices[T]()
      op(op.attr, attr).result
    }
    def apply(params: Map[String, String]) = {
      val m = merge(project.vertexAttributes(params("key")))
      val oldVAttrs = project.vertexAttributes.toMap
      val oldEdges = project.edgeBundle
      val oldEAttrs = project.edgeAttributes.toMap
      project.setVertexSet(m.segments, idAttr = "id")
      // Always use most_common for the key attribute.
      val hack = "aggregate-" + params("key") -> "most_common"
      for ((attr, choice) <- parseAggregateParams(params + hack)) {
        val result = aggregateViaConnection(
          m.belongsTo,
          attributeWithLocalAggregator(oldVAttrs(attr), choice))
        project.vertexAttributes(attr) = result
      }
      val edgeInduction = {
        val op = graph_operations.InducedEdgeBundle()
        op(op.srcMapping, m.belongsTo)(op.dstMapping, m.belongsTo)(op.edges, oldEdges).result
      }
      project.edgeBundle = edgeInduction.induced
      for ((name, eAttr) <- oldEAttrs) {
        project.edgeAttributes(name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(
            eAttr, edgeInduction.embedding)
      }
    }
  })

  register(new EdgeOperation(_) {
    val title = "Merge parallel edges"
    val description = ""

    def parameters =
      aggregateParams(
        project.edgeAttributes.map { case (name, ea) => (name, ea) })

    def enabled = hasEdgeBundle

    def merge[T](attr: VertexAttribute[T]): graph_operations.MergeVertices.Output = {
      val op = graph_operations.MergeVertices[T]()
      op(op.attr, attr).result
    }
    def apply(params: Map[String, String]) = {
      val edgesAsAttr = {
        val op = graph_operations.EdgeBundleAsVertexAttribute()
        op(op.edges, project.edgeBundle).result.attr
      }
      val mergedResult = {
        val op = graph_operations.MergeVertices[(ID, ID)]()
        op(op.attr, edgesAsAttr).result
      }
      val newEdges = {
        val op = graph_operations.PulledOverEdges()
        op(op.originalEB, project.edgeBundle)(op.injection, mergedResult.representative)
          .result.pulledEB
      }
      val oldAttrs = project.edgeAttributes.toMap
      project.edgeBundle = newEdges

      for ((attrName, choice) <- parseAggregateParams(params)) {
        project.edgeAttributes(attrName) =
          aggregateViaConnection(
            mergedResult.belongsTo,
            attributeWithLocalAggregator(oldAttrs(attrName), choice))
      }
    }
  })

  register(new EdgeOperation(_) {
    val title = "Discard loop edges"
    val description = "Discards edges that connect a vertex to itself."
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val edgesAsAttr = {
        val op = graph_operations.EdgeBundleAsVertexAttribute()
        op(op.edges, project.edgeBundle).result.attr
      }
      val guid = edgesAsAttr.entity.gUID.toString
      val embedding = FEFilters.embedFilteredVertices(
        project.edgeBundle.asVertexSet,
        Seq(FEVertexAttributeFilter(guid, "!=")))
      project.pullBackEdgesWithInjection(embedding)
    }
  })

  register(new AttributeOperation(_) {
    val title = "Aggregate vertex attribute globally"
    val description = "The result is a single scalar value."
    def parameters = List(Param("prefix", "Generated name prefix", defaultValue = "")) ++
      aggregateParams(project.vertexAttributes, needsGlobal = true)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(attributeWithAggregator(project.vertexAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}"
        project.scalars(name) = result
      }
    }
  })

  register(new AttributeOperation(_) {
    val title = "Weighted aggregate vertex attribute globally"
    val description = "The result is a single scalar value."
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = ""),
      Param("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(project.vertexAttributes, needsGlobal = true, weighted = true)
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          attributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}_by_${weightName}"
        project.scalars(name) = result
      }
    }
  })

  register(new AttributeOperation(_) {
    val title = "Aggregate edge attribute globally"
    val description = "The result is a single scalar value."
    def parameters = List(Param("prefix", "Generated name prefix", defaultValue = "")) ++
      aggregateParams(
        project.edgeAttributes.map { case (name, ea) => (name, ea) },
        needsGlobal = true)
    def enabled =
      FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          attributeWithAggregator(project.edgeAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}"
        project.scalars(name) = result
      }
    }
  })

  register(new AttributeOperation(_) {
    val title = "Weighted aggregate edge attribute globally"
    val description = "The result is a single scalar value."
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = ""),
      Param("weight", "Weight", options = edgeAttributes[Double])) ++
      aggregateParams(
        project.edgeAttributes.map { case (name, ea) => (name, ea) },
        needsGlobal = true, weighted = true)
    def enabled =
      FEStatus.assert(edgeAttributes[Double].nonEmpty, "No numeric edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.edgeAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          attributeWithWeightedAggregator(weight, project.edgeAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}_by_${weightName}"
        project.scalars(name) = result
      }
    }
  })

  register(new AttributeOperation(_) {
    val title = "Aggregate edge attribute to vertices"
    val description =
      "For example it can calculate the average duration of calls for each person."
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "edge"),
      Param("direction", "Aggregate on",
        options = UIValue.list(List("incoming edges", "outgoing edges")))) ++
      aggregateParams(
        project.edgeAttributes.map { case (name, ea) => (name, ea) })
    def enabled =
      FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateFromEdges(
          project.edgeBundle,
          params("direction") == "outgoing edges",
          attributeWithLocalAggregator(
            project.edgeAttributes(attr),
            choice))
        project.vertexAttributes(s"${prefix}${attr}_${choice}") = result
      }
    }
  })

  register(new AttributeOperation(_) {
    val title = "Weighted aggregate edge attribute to vertices"
    val description =
      "For example it can calculate the average cost per second of calls for each person."
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "edge"),
      Param("weight", "Weight", options = edgeAttributes[Double]),
      Param("direction", "Aggregate on",
        options = UIValue.list(List("incoming edges", "outgoing edges")))) ++
      aggregateParams(
        project.edgeAttributes.map { case (name, ea) => (name, ea) },
        weighted = true)
    def enabled =
      FEStatus.assert(edgeAttributes[Double].nonEmpty, "No numeric edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.edgeAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateFromEdges(
          project.edgeBundle,
          params("direction") == "outgoing edges",
          attributeWithWeightedAggregator(
            weight,
            project.edgeAttributes(attr),
            choice))
        project.vertexAttributes(s"${prefix}${attr}_${choice}_by_${weightName}") = result
      }
    }
  })

  register(new HiddenOperation(_) {
    val title = "Discard edge attribute"
    def parameters = List(
      Param("name", "Name", options = edgeAttributes))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      project.edgeAttributes(params("name")) = null
    }
  })

  register(new HiddenOperation(_) {
    val title = "Discard vertex attribute"
    def parameters = List(
      Param("name", "Name", options = vertexAttributes))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      project.vertexAttributes(params("name")) = null
    }
  })

  register(new HiddenOperation(_) {
    val title = "Discard segmentation"
    def parameters = List(
      Param("name", "Name", options = segmentations))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    def apply(params: Map[String, String]) = {
      project.segmentation(params("name")).remove
    }
  })

  register(new HiddenOperation(_) {
    val title = "Discard scalar"
    def parameters = List(
      Param("name", "Name", options = scalars))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    def apply(params: Map[String, String]) = {
      project.scalars(params("name")) = null
    }
  })

  register(new HiddenOperation(_) {
    val title = "Rename edge attribute"
    def parameters = List(
      Param("from", "Old name", options = edgeAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      assert(!project.edgeAttributes.contains(params("to")),
        s"""An edge-attribute named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.edgeAttributes(params("to")) = project.edgeAttributes(params("from"))
      project.edgeAttributes(params("from")) = null
    }
  })

  register(new HiddenOperation(_) {
    val title = "Rename vertex attribute"
    def parameters = List(
      Param("from", "Old name", options = vertexAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      assert(!project.vertexAttributes.contains(params("to")),
        s"""A vertex-attribute named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.vertexAttributes(params("to")) = project.vertexAttributes(params("from"))
      project.vertexAttributes(params("from")) = null
    }
  })

  register(new HiddenOperation(_) {
    val title = "Rename segmentation"
    def parameters = List(
      Param("from", "Old name", options = segmentations),
      Param("to", "New name"))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    def apply(params: Map[String, String]) = {
      assert(!project.segmentations.contains(params("to")),
        s"""A segmentation named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.segmentation(params("from")).rename(params("to"))
    }
  })

  register(new HiddenOperation(_) {
    val title = "Rename scalar"
    def parameters = List(
      Param("from", "Old name", options = scalars),
      Param("to", "New name"))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    def apply(params: Map[String, String]) = {
      assert(!project.scalars.contains(params("to")),
        s"""A scalar named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.scalars(params("to")) = project.scalars(params("from"))
      project.scalars(params("from")) = null
    }
  })

  register(new HiddenOperation(_) {
    val title = "Copy edge attribute"
    def parameters = List(
      Param("from", "Old name", options = edgeAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      project.edgeAttributes(params("to")) = project.edgeAttributes(params("from"))
    }
  })

  register(new HiddenOperation(_) {
    val title = "Copy vertex attribute"
    def parameters = List(
      Param("from", "Old name", options = vertexAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      project.vertexAttributes(params("to")) = project.vertexAttributes(params("from"))
    }
  })

  register(new HiddenOperation(_) {
    val title = "Copy segmentation"
    def parameters = List(
      Param("from", "Old name", options = segmentations),
      Param("to", "New name"))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    def apply(params: Map[String, String]) = {
      val from = project.segmentation(params("from"))
      val to = project.segmentation(params("to"))
      from.project.copy(to.project)
      to.belongsTo = from.belongsTo
    }
  })

  register(new HiddenOperation(_) {
    val title = "Copy scalar"
    def parameters = List(
      Param("from", "Old name", options = scalars),
      Param("to", "New name"))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    def apply(params: Map[String, String]) = {
      project.scalars(params("to")) = project.scalars(params("from"))
    }
  })

  register(new CreateSegmentationOperation(_) {
    val title = "Import project as segmentation"
    val description =
      """Copies another project into a new segmentation for this one. There will be no
      connections between the segments and the base vertices. You can import/create those via
      a new operation."""
    def parameters = List(
      Param("them", "Other project's name", options = otherProjects))
    private def otherProjects = uIProjects.filter(_.id != project.projectName)
    def enabled =
      hasVertexSet &&
        FEStatus.assert(otherProjects.size > 0, "This is the only project")
    def apply(params: Map[String, String]) = {
      val them = Project(params("them"))
      assert(them.vertexSet != null, s"No vertex set in $them")
      val segmentation = project.segmentation(params("them"))
      them.copy(segmentation.project)
      val op = graph_operations.EmptyEdgeBundle()
      segmentation.belongsTo = op(op.src, project.vertexSet)(op.dst, them.vertexSet).result.eb
      segmentation.project.discardCheckpoints()
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Load segmentation links from CSV"
    val description =
      "Import the connection between the main project and this segmentation from a CSV." +
        importHelpText
    def parameters = List(
      Param("files", "CSV", kind = "file"),
      Param("header", "Header", defaultValue = "<read first line>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param(
        "base-id-attr",
        s"Identifying vertex attribute in $parent",
        options = UIValue.list(parent.vertexAttributeNames[String].toList)),
      Param("base-id-field", s"Identifying CSV field for $parent"),
      Param(
        "seg-id-attr",
        s"Identifying vertex attribute in $project",
        options = vertexAttributes[String]),
      Param("seg-id-field", s"Identifying CSV field for $project"))
    def enabled =
      FEStatus.assert(
        vertexAttributes[String].nonEmpty, "No string vertex attributes in this segmentation") &&
        FEStatus.assert(
          parent.vertexAttributeNames[String].nonEmpty, "No string vertex attributes in parent")
    def apply(params: Map[String, String]) = {
      val baseIdAttr = parent.vertexAttributes(params("base-id-attr")).runtimeSafeCast[String]
      val segIdAttr = project.vertexAttributes(params("seg-id-attr")).runtimeSafeCast[String]
      val files = Filename(params("files"))
      val header = if (params("header") == "<read first line>")
        graph_operations.ImportUtil.header(files) else params("header")
      val csv = graph_operations.CSV(
        files,
        params("delimiter"),
        header)
      val op = graph_operations.ImportEdgeListForExistingVertexSet(
        csv, params("base-id-field"), params("seg-id-field"))
      seg.belongsTo = op(op.srcVidAttr, baseIdAttr)(op.dstVidAttr, segIdAttr).result.edges
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Define segmentation links from matching attributes"
    val description =
      "Connection vertices in the main project with segmentations based on matching attributes."
    def parameters = List(
      Param(
        "base-id-attr",
        s"Identifying vertex attribute in $parent",
        options = UIValue.list(parent.vertexAttributeNames[String].toList)),
      Param(
        "seg-id-attr",
        s"Identifying vertex attribute in $project",
        options = vertexAttributes[String]))
    def enabled =
      FEStatus.assert(
        vertexAttributes[String].nonEmpty, "No string vertex attributes in this segmentation") &&
        FEStatus.assert(
          parent.vertexAttributeNames[String].nonEmpty, "No string vertex attributes in parent")
    def apply(params: Map[String, String]) = {
      val baseIdAttr = parent.vertexAttributes(params("base-id-attr")).runtimeSafeCast[String]
      val segIdAttr = project.vertexAttributes(params("seg-id-attr")).runtimeSafeCast[String]
      val op = graph_operations.EdgesFromBipartiteAttributeMatches[String]()
      seg.belongsTo = op(op.fromAttr, baseIdAttr)(op.toAttr, segIdAttr).result.edges
    }
  })

  register(new VertexOperation(_) {
    val title = "Union with another project"
    val description =
      """The resulting graph is just a disconnected graph containing the vertices and edges of
      the two originating projects. All vertex and edge attributes are preserved. If an attribute
      exists in both projects, it must have the same data type in both.
      """
    def parameters = List(
      Param("other", "Other project's name", options = uIProjects),
      Param("id-attr", "ID attribute name", defaultValue = "new_id"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]): Unit = {
      val other = Project(params("other"))
      if (other.vertexSet == null) {
        // Nothing to do
        return
      }
      val vsUnion = {
        val op = graph_operations.VertexSetUnion(2)
        op(op.vss, Seq(project.vertexSet, other.vertexSet)).result
      }
      val newVertexAttributes = unifyAttributes(
        project.vertexAttributes
          .map {
            case (name, attr) =>
              name -> graph_operations.PulledOverVertexAttribute.pullAttributeVia(
                attr,
                reverse(vsUnion.injections(0)))
          },
        other.vertexAttributes
          .map {
            case (name, attr) =>
              name -> graph_operations.PulledOverVertexAttribute.pullAttributeVia(
                attr,
                reverse(vsUnion.injections(1)))
          })
      val ebInduced = Option(project.edgeBundle).map { eb =>
        val op = graph_operations.InducedEdgeBundle()
        val mapping = vsUnion.injections(0)
        op(op.srcMapping, mapping)(op.dstMapping, mapping)(op.edges, project.edgeBundle).result
      }
      val otherEbInduced = Option(other.edgeBundle).map { eb =>
        val op = graph_operations.InducedEdgeBundle()
        val mapping = vsUnion.injections(1)
        op(op.srcMapping, mapping)(op.dstMapping, mapping)(op.edges, other.edgeBundle).result
      }

      val (newEdgeBundle, myEbInjection, otherEbInjection): (EdgeBundle, EdgeBundle, EdgeBundle) =
        if (ebInduced.isDefined && !otherEbInduced.isDefined) {
          (ebInduced.get.induced.entity, ebInduced.get.embedding, null)
        } else if (!ebInduced.isDefined && otherEbInduced.isDefined) {
          (otherEbInduced.get.induced.entity, null, otherEbInduced.get.embedding)
        } else {
          assert(ebInduced.isDefined && otherEbInduced.isDefined)
          val idUnion = {
            val op = graph_operations.VertexSetUnion(2)
            op(
              op.vss,
              Seq(ebInduced.get.induced.asVertexSet, otherEbInduced.get.induced.asVertexSet))
              .result
          }
          val ebUnion = {
            val op = graph_operations.EdgeBundleUnion(2)
            op(
              op.ebs, Seq(ebInduced.get.induced.entity, otherEbInduced.get.induced.entity))(
                op.injections, idUnion.injections.map(_.entity)).result.union
          }
          (ebUnion,
            concat(reverse(idUnion.injections(0).entity), ebInduced.get.embedding),
            concat(reverse(idUnion.injections(1).entity), otherEbInduced.get.embedding))
        }
      val newEdgeAttributes = unifyAttributes(
        project.edgeAttributes
          .map {
            case (name, attr) => {
              name -> graph_operations.PulledOverVertexAttribute.pullAttributeVia(
                attr,
                myEbInjection)
            }
          },
        other.edgeAttributes
          .map {
            case (name, attr) =>
              name -> graph_operations.PulledOverVertexAttribute.pullAttributeVia(
                attr,
                otherEbInjection)
          })

      project.vertexSet = vsUnion.union
      project.vertexAttributes = newVertexAttributes
      val idAttr = params("id-attr")
      assert(
        !project.vertexAttributes.contains(idAttr),
        s"The project already contains a field called '$idAttr'. Please pick a different name.")
      project.vertexAttributes(idAttr) = idAsAttribute(project.vertexSet)
      project.edgeBundle = newEdgeBundle
      project.edgeAttributes = newEdgeAttributes
    }
  })

  register(new VertexOperation(_) {
    val title = "Fingerprinting based on attributes"
    val description =
      """In a graph that has two different string identifier attributes (e.g. Facebook ID and
      MSISDN) this operation will match the vertices that only have the first attribute defined
      with the vertices that only have the second attribute defined. For the well-matched vertices
      the new attributes will be added. (For example if a vertex only had an MSISDN and we found a
      matching Facebook ID, this will be saved as the Facebook ID of the vertex.)

      <p>The matched vertices will not be automatically merged, but this can easily be performed
      with the "Merge vertices by attribute" operation on either of the two identifier attributes.
      """
    def parameters = List(
      Param("leftName", "First ID attribute", options = vertexAttributes[String]),
      Param("rightName", "Second ID attribute", options = vertexAttributes[String]),
      Param("weights", "Edge weights",
        options = UIValue("no weights", "no weights") +: edgeAttributes[Double]),
      Param("mrew", "Minimum relative edge weight", defaultValue = "0.0"),
      Param("mo", "Minimum overlap", defaultValue = "1"),
      Param("ms", "Minimum similarity", defaultValue = "0.5"))
    def enabled =
      hasEdgeBundle &&
        FEStatus.assert(vertexAttributes[String].size >= 2, "Two string attributes are needed.")
    def apply(params: Map[String, String]): Unit = {
      val mrew = params("mrew").toDouble
      val mo = params("mo").toInt
      val ms = params("ms").toDouble
      assert(mo >= 1, "Minimum overlap cannot be less than 1.")
      val leftName = project.vertexAttributes(params("leftName")).runtimeSafeCast[String]
      val rightName = project.vertexAttributes(params("rightName")).runtimeSafeCast[String]
      val weights = if (params("weights") == "no weights") {
        const(project.edgeBundle)
      } else {
        project.edgeAttributes(params("weights")).runtimeSafeCast[Double]
      }

      // TODO: Calculate relative edge weight, filter the edge bundle and pull over the weights.
      assert(mrew == 0, "Minimum relative edge weight is not implemented yet.")

      val candidates = {
        val op = graph_operations.FingerprintingCandidates()
        op(op.es, project.edgeBundle)(op.leftName, leftName)(op.rightName, rightName)
          .result.candidates
      }
      val fingerprinting = {
        val op = graph_operations.Fingerprinting(mo, ms)
        op(
          op.leftEdges, project.edgeBundle)(
            op.leftEdgeWeights, weights)(
              op.rightEdges, project.edgeBundle)(
                op.rightEdgeWeights, weights)(
                  op.candidates, candidates)
          .result
      }
      val newLeftName = graph_operations.PulledOverVertexAttribute.pullAttributeVia(
        leftName, reverse(fingerprinting.matching))
      val newRightName = graph_operations.PulledOverVertexAttribute.pullAttributeVia(
        rightName, fingerprinting.matching)

      project.scalars("fingerprinting matches found") = count(fingerprinting.matching)
      project.vertexAttributes(params("leftName")) = unifyAttribute(newLeftName, leftName)
      project.vertexAttributes(params("rightName")) = unifyAttribute(newRightName, rightName)
      project.vertexAttributes(params("leftName") + " similarity score") = fingerprinting.leftSimilarities
      project.vertexAttributes(params("rightName") + " similarity score") = fingerprinting.rightSimilarities
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Copy vertex attributes from segmentation"
    val description =
      "Copies all vertex attributes from the segmentation to the parent."
    def parameters = List(
      Param("prefix", "Attribute name prefix", defaultValue = seg.name))
    def enabled =
      FEStatus.assert(vertexAttributes.size > 0, "No vertex attributes") &&
        FEStatus.assert(parent.vertexSet != null, s"No vertices on $parent") &&
        FEStatus.assert(seg.belongsTo.properties.isFunction,
          s"Vertices of $parent are not guaranteed to have only one edge to this segmentation")
    def apply(params: Map[String, String]): Unit = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- project.vertexAttributes.toMap) {
        parent.vertexAttributes(prefix + name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(
            attr, seg.belongsTo)
      }
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Copy vertex attributes to segmentation"
    val description =
      "Copies all vertex attributes from the parent to the segmentation."
    def parameters = List(
      Param("prefix", "Attribute name prefix"))
    def enabled =
      hasVertexSet &&
        FEStatus.assert(parent.vertexAttributes.size > 0, "No vertex attributes on $parent") &&
        FEStatus.assert(seg.belongsTo.properties.isReversedFunction,
          s"Vertices of this segmentation are not guaranteed to have only one edge from $parent")
    def apply(params: Map[String, String]): Unit = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- parent.vertexAttributes.toMap) {
        project.vertexAttributes(prefix + name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(
            attr, reverse(seg.belongsTo))
      }
    }
  })

  register(new SegmentationOperation(_) {
    val title = "Fingerprinting between project and segmentation"
    val description =
      """Finds the best match out of the potential matches that are defined between a project and
      a segmentation. The best match is chosen by comparing the vertex neighborhoods in the project
      and the segmentation.

      <p>The result of this operation is an updated edge set between the project and the
      segmentation, that is a one-to-one matching.

      <p>Example use-case: Project M is an MSISDN graph based on call data. Project F is a Facebook
      graph. A CSV file contains a number of MSISDN -> Facebook ID mappings, a many-to-many
      relationship. Connect the two projects with "Import project as segmentation", then use this
      operation to turn the mapping into a high-quality one-to-one relationship.
      """
    def parameters = List(
      Param("mrew", "Minimum relative edge weight", defaultValue = "0.0"),
      Param("mo", "Minimum overlap", defaultValue = "1"),
      Param("ms", "Minimum similarity", defaultValue = "0.5"))
    def enabled =
      hasEdgeBundle && FEStatus.assert(parent.edgeBundle != null, s"No edges on $parent")
    def apply(params: Map[String, String]): Unit = {
      val mrew = params("mrew").toDouble
      val mo = params("mo").toInt
      val ms = params("ms").toDouble

      // TODO: Calculate relative edge weight, filter the edge bundle and pull over the weights.
      assert(mrew == 0, "Minimum relative edge weight is not implemented yet.")

      val candidates = seg.belongsTo
      val segNeighborsInParent = concat(project.edgeBundle, reverse(seg.belongsTo))
      val fingerprinting = {
        val op = graph_operations.Fingerprinting(mo, ms)
        op(
          op.leftEdges, parent.edgeBundle)(
            op.leftEdgeWeights, const(parent.edgeBundle))(
              op.rightEdges, segNeighborsInParent)(
                op.rightEdgeWeights, const(segNeighborsInParent))(
                  op.candidates, candidates)
          .result
      }

      project.scalars("fingerprinting matches found") = count(fingerprinting.matching)
      seg.belongsTo = fingerprinting.matching
      parent.vertexAttributes("fingerprinting similarity score") = fingerprinting.leftSimilarities
      project.vertexAttributes("fingerprinting similarity score") = fingerprinting.rightSimilarities
    }
  })

  register(new HiddenOperation(_) {
    val title = "Change project notes"
    def parameters = List(
      Param("notes", "New contents"))
    def enabled = FEStatus.enabled
    def apply(params: Map[String, String]) = {
      project.notes = params("notes")
    }
  })

  register(new SegmentationWorkflowOperation(_) {
    val title = "Viral modeling"
    val description = """Viral modeling tries to predict unknown values of an attribute based on
        the known values of the attribute on peers that belong to the same segments."""
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "viral"),
      Param("target", "Target attribute",
        options = UIValue.list(parentDoubleAttributes)),
      Param("test_set_ratio", "Test set ratio", defaultValue = "0.1"),
      Param("max_deviation", "Maximal segment deviation", defaultValue = "1.0"),
      Param("seed", "Seed", defaultValue = "0"),
      Param("iterations", "Iterations", defaultValue = "3"))
    def parentDoubleAttributes = parent.vertexAttributeNames[Double].toList
    def enabled = hasVertexSet &&
      FEStatus.assert(UIValue.list(parentDoubleAttributes).nonEmpty,
        "No numeric vertex attributes.")
    def apply(params: Map[String, String]) = {
      // partition target attribute to test and train sets
      val targetName = params("target")
      val target = parent.vertexAttributes(targetName).runtimeSafeCast[Double]
      val roles = {
        val op = graph_operations.CreateRole(params("test_set_ratio").toDouble, params("seed").toInt)
        op(op.vertices, target.vertexSet).result.role
      }
      val parted = {
        val op = graph_operations.PartitionAttribute[Double]()
        op(op.attr, target)(op.role, roles).result
      }
      val prefix = params("prefix")
      parent.vertexAttributes(s"$prefix roles") = roles
      parent.vertexAttributes(s"$prefix $targetName test") = parted.test
      var train = parted.train.entity
      val segSizes = computeSegmentSizes(seg)
      project.vertexAttributes("size") = segSizes
      val maxDeviation = params("max_deviation")

      val coverage = {
        val op = graph_operations.CountAttributes[Double]()
        op(op.attribute, train).result.count
      }
      parent.vertexAttributes(s"$prefix $targetName train") = train
      parent.scalars(s"$prefix $targetName coverage initial") = coverage

      var timeOfDefinition = {
        val op = graph_operations.DeriveJSDouble(
          JavaScript("0"), Seq("attr"), Seq(), Seq())
        op(op.numAttrs, Seq(train)).result.attr.entity
      }

      // iterative prediction
      for (i <- 1 to params("iterations").toInt) {
        val segTargetAvg = {
          aggregateViaConnection(
            seg.belongsTo,
            attributeWithLocalAggregator(train, "average"))
            .runtimeSafeCast[Double]
        }
        val segStdDev = {
          aggregateViaConnection(
            seg.belongsTo,
            attributeWithLocalAggregator(train, "std_deviation"))
            .runtimeSafeCast[Double]
        }
        val segTargetCount = {
          aggregateViaConnection(
            seg.belongsTo,
            attributeWithLocalAggregator(train, "count"))
            .runtimeSafeCast[Double]
        }
        val segStdDevDefined = {
          // 50% should be defined in order to consider a segmentation, is that good enough?
          val op = graph_operations.DeriveJSDouble(
            JavaScript(s"""
                deviation < $maxDeviation && defined / ids >= 0.5
                ? deviation
                : undefined"""),
            Seq("deviation", "ids", "defined"), Seq(), Seq())
          op(op.numAttrs, Seq(segStdDev, segSizes, segTargetCount)).result.attr
        }
        project.vertexAttributes(s"$prefix $targetName standard deviation after iteration $i") =
          segStdDev
        project.vertexAttributes(s"$prefix $targetName average after iteration $i") =
          segTargetAvg // TODO: use median
        val predicted = {
          aggregateViaConnection(
            reverse(seg.belongsTo),
            attributeWithWeightedAggregator(segStdDevDefined, segTargetAvg, "by_min_weight"))
            .runtimeSafeCast[Double]
        }
        train = unifyAttributeT(train, predicted)
        val partedTrain = {
          val op = graph_operations.PartitionAttribute[Double]()
          op(op.attr, train)(op.role, roles).result
        }
        val error = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript("Math.abs(test - train)"), Seq("test", "train"), Seq(), Seq())
          val mae = op(op.numAttrs, Seq(parted.test.entity, partedTrain.test.entity)).result.attr
          aggregate(attributeWithAggregator(mae, "average"))
        }
        val coverage = {
          val op = graph_operations.CountAttributes[Double]()
          op(op.attribute, partedTrain.train).result.count
        }
        // the attribute we use for iteration can be defined on the test set as well
        parent.vertexAttributes(s"$prefix $targetName after iteration $i") = train
        parent.scalars(s"$prefix $targetName coverage after iteration $i") = coverage
        parent.scalars(s"$prefix $targetName mean absolute prediction error after iteration $i") =
          error

        timeOfDefinition = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript(i.toString), Seq("attr"), Seq(), Seq())
          val newDefinitions = op(op.numAttrs, Seq(train)).result.attr
          unifyAttributeT(timeOfDefinition, newDefinitions)
        }
      }
      parent.vertexAttributes(s"$prefix $targetName spread over iterations") = timeOfDefinition
      // TODO: in the end we should calculate with the fact that the real error where the
      // original attribute is defined is 0.0
    }
  })

  { // "Dirty operations", that is operations that use a data manager. Think twice if you really
    // need this before putting an operation here.
    implicit val dataManager = env.dataManager

    register(new AttributeOperation(_) {
      val title = "Export vertex attributes to CSV"
      val description = ""
      def parameters = List(
        Param("path", "Destination path", defaultValue = "<auto>"),
        Param("link", "Download link name", defaultValue = "vertex_attributes_csv"),
        Param("attrs", "Attributes", options = vertexAttributes, multipleChoice = true))
      def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes.")
      def apply(params: Map[String, String]) = {
        assert(params("attrs").nonEmpty, "Nothing selected for export.")
        val labels = params("attrs").split(",")
        val attrs = labels.map(label => project.vertexAttributes(label))
        val path = getExportFilename(params("path"))
        val csv = graph_util.CSVExport.exportVertexAttributes(attrs, labels)
        csv.saveToDir(path)
        project.scalars(params("link")) =
          downloadLink(path, project.projectName + "_" + params("link"))
      }
    })

    def getExportFilename(param: String): Filename = {
      assert(param.nonEmpty, "No export path specified.")
      if (param == "<auto>") {
        dataManager.repositoryPath / "exports" / Timestamp.toString
      } else {
        Filename(param)
      }
    }

    register(new AttributeOperation(_) {
      val title = "Export edge attributes to CSV"
      val description = ""
      def parameters = List(
        Param("path", "Destination path", defaultValue = "<auto>"),
        Param("link", "Download link name", defaultValue = "edge_attributes_csv"),
        Param("attrs", "Attributes", options = edgeAttributes, multipleChoice = true))
      def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes.")
      def apply(params: Map[String, String]) = {
        assert(params("attrs").nonEmpty, "Nothing selected for export.")
        val labels = params("attrs").split(",")
        val attrs = labels.map(label => project.edgeAttributes(label))
        val path = getExportFilename(params("path"))
        val csv = graph_util.CSVExport
          .exportEdgeAttributes(project.edgeBundle, attrs, labels)
        csv.saveToDir(path)
        project.scalars(params("link")) =
          downloadLink(path, project.projectName + "_" + params("link"))
      }
    })

    register(new SegmentationOperation(_) {
      val title = "Export segmentation to CSV"
      val description = ""
      def parameters = List(
        Param("path", "Destination path", defaultValue = "<auto>"),
        Param("link", "Download link name", defaultValue = "segmentation_csv"))
      def enabled = FEStatus.enabled
      def apply(params: Map[String, String]) = {
        val path = getExportFilename(params("path"))
        val csv = graph_util.CSVExport
          .exportEdgeAttributes(seg.belongsTo, Seq(), Seq())
        csv.saveToDir(path)
        project.scalars(params("link")) =
          downloadLink(path, project.projectName + "_" + params("link"))
      }
    })
  }

  def joinAttr[A, B](a: VertexAttribute[A], b: VertexAttribute[B]): VertexAttribute[(A, B)] = {
    val op = graph_operations.JoinAttributes[A, B]()
    op(op.a, a)(op.b, b).result.attr
  }

  def computeSegmentSizes(segmentation: Segmentation): VertexAttribute[Double] = {
    val op = graph_operations.OutDegree()
    op(op.es, reverse(segmentation.belongsTo)).result.outDegree
  }

  def toDouble(attr: VertexAttribute[String]): VertexAttribute[Double] = {
    val op = graph_operations.VertexAttributeToDouble()
    op(op.attr, attr).result.attr
  }

  def parseAggregateParams(params: Map[String, String]) = {
    val aggregate = "aggregate-(.*)".r
    params.collect {
      case (aggregate(attr), choice) if choice != "ignore" => attr -> choice
    }
  }
  def aggregateParams(
    attrs: Iterable[(String, VertexAttribute[_])],
    needsGlobal: Boolean = false,
    weighted: Boolean = false): List[FEOperationParameterMeta] = {
    attrs.toList.map {
      case (name, attr) =>
        val options = if (attr.is[Double]) {
          if (weighted) { // At the moment all weighted aggregators are global.
            UIValue.list(List("ignore", "weighted_sum", "weighted_average", "by_max_weight", "by_min_weight"))
          } else if (needsGlobal) {
            UIValue.list(List("ignore", "sum", "average", "min", "max", "count", "first", "std_deviation"))
          } else {
            UIValue.list(List("ignore", "sum", "average", "min", "max", "most_common", "count", "vector", "std_deviation"))
          }
        } else if (attr.is[String]) {
          if (weighted) { // At the moment all weighted aggregators are global.
            UIValue.list(List("ignore", "by_max_weight", "by_min_weight"))
          } else if (needsGlobal) {
            UIValue.list(List("ignore", "count", "first"))
          } else {
            UIValue.list(List("ignore", "most_common", "majority_50", "majority_100", "count", "vector"))
          }
        } else {
          if (weighted) { // At the moment all weighted aggregators are global.
            UIValue.list(List("ignore", "by_max_weight", "by_min_weight"))
          } else if (needsGlobal) {
            UIValue.list(List("ignore", "count", "first"))
          } else {
            UIValue.list(List("ignore", "most_common", "count", "vector"))
          }
        }
        Param(s"aggregate-$name", name, options = options)
    }
  }

  trait AttributeWithLocalAggregator[From, To] {
    val attr: VertexAttribute[From]
    val aggregator: graph_operations.LocalAggregator[From, To]
  }
  object AttributeWithLocalAggregator {
    def apply[From, To](
      attrInp: VertexAttribute[From],
      aggregatorInp: graph_operations.LocalAggregator[From, To]): AttributeWithLocalAggregator[From, To] = {
      new AttributeWithLocalAggregator[From, To] {
        val attr = attrInp
        val aggregator = aggregatorInp
      }
    }
  }

  case class AttributeWithAggregator[From, Intermediate, To](
    val attr: VertexAttribute[From],
    val aggregator: graph_operations.Aggregator[From, Intermediate, To])
      extends AttributeWithLocalAggregator[From, To]

  private def attributeWithAggregator[T](
    attr: VertexAttribute[T], choice: String): AttributeWithAggregator[_, _, _] = {
    choice match {
      case "sum" => AttributeWithAggregator(attr.runtimeSafeCast[Double], graph_operations.Aggregator.Sum())
      case "count" => AttributeWithAggregator(attr, graph_operations.Aggregator.Count[T]())
      case "min" => AttributeWithAggregator(attr.runtimeSafeCast[Double], graph_operations.Aggregator.Min())
      case "max" => AttributeWithAggregator(attr.runtimeSafeCast[Double], graph_operations.Aggregator.Max())
      case "average" => AttributeWithAggregator(
        attr.runtimeSafeCast[Double], graph_operations.Aggregator.Average())
      case "first" => AttributeWithAggregator(attr, graph_operations.Aggregator.First[T]())
      case "std_deviation" => AttributeWithAggregator(
        attr.runtimeSafeCast[Double], graph_operations.Aggregator.StdDev())
    }
  }
  private def attributeWithWeightedAggregator[T](
    weight: VertexAttribute[Double], attr: VertexAttribute[T], choice: String): AttributeWithAggregator[_, _, _] = {
    choice match {
      case "by_max_weight" => AttributeWithAggregator(
        joinAttr(weight, attr), graph_operations.Aggregator.MaxBy[Double, T]())
      case "by_min_weight" => AttributeWithAggregator(
        joinAttr(graph_operations.DeriveJS.negative(weight), attr), graph_operations.Aggregator.MaxBy[Double, T]())
      case "weighted_sum" => AttributeWithAggregator(
        joinAttr(weight, attr.runtimeSafeCast[Double]), graph_operations.Aggregator.WeightedSum())
      case "weighted_average" => AttributeWithAggregator(
        joinAttr(weight, attr.runtimeSafeCast[Double]), graph_operations.Aggregator.WeightedAverage())
    }
  }

  private def attributeWithLocalAggregator[T](
    attr: VertexAttribute[T], choice: String): AttributeWithLocalAggregator[_, _] = {
    choice match {
      case "most_common" => AttributeWithLocalAggregator(attr, graph_operations.Aggregator.MostCommon[T]())
      case "majority_50" => AttributeWithLocalAggregator(attr.runtimeSafeCast[String], graph_operations.Aggregator.Majority(0.5))
      case "majority_100" => AttributeWithLocalAggregator(attr.runtimeSafeCast[String], graph_operations.Aggregator.Majority(1.0))
      case "vector" => AttributeWithLocalAggregator(attr, graph_operations.Aggregator.AsVector[T]())
      case _ => attributeWithAggregator(attr, choice)
    }
  }

  // Performs AggregateAttributeToScalar.
  private def aggregate[From, Intermediate, To](
    attributeWithAggregator: AttributeWithAggregator[From, Intermediate, To]): Scalar[To] = {
    val op = graph_operations.AggregateAttributeToScalar(attributeWithAggregator.aggregator)
    op(op.attr, attributeWithAggregator.attr).result.aggregated
  }

  // Performs AggregateByEdgeBundle.
  def aggregateViaConnection[From, To](
    connection: EdgeBundle,
    attributeWithAggregator: AttributeWithLocalAggregator[From, To]): VertexAttribute[To] = {
    val op = graph_operations.AggregateByEdgeBundle(attributeWithAggregator.aggregator)
    op(op.connection, connection)(op.attr, attributeWithAggregator.attr).result.attr
  }

  // Performs AggregateFromEdges.
  def aggregateFromEdges[From, To](
    edges: EdgeBundle,
    onSrc: Boolean,
    attributeWithAggregator: AttributeWithLocalAggregator[From, To]): VertexAttribute[To] = {
    val op = graph_operations.AggregateFromEdges(attributeWithAggregator.aggregator)
    val res = op(op.edges, edges)(op.eattr, attributeWithAggregator.attr).result
    if (onSrc) res.srcAttr else res.dstAttr
  }

  def reverse(eb: EdgeBundle): EdgeBundle = {
    val op = graph_operations.ReverseEdges()
    op(op.esAB, eb).result.esBA
  }

  def count(eb: EdgeBundle): Scalar[Long] = {
    val op = graph_operations.CountEdges()
    op(op.edges, eb).result.count
  }

  private def unifyAttributeT[T](a1: VertexAttribute[T], a2: VertexAttribute[_]): VertexAttribute[T] = {
    val op = graph_operations.AttributeFallback[T]()
    op(op.originalAttr, a1)(op.defaultAttr, a2.runtimeSafeCast(a1.typeTag)).result.defaultedAttr
  }
  def unifyAttribute(a1: VertexAttribute[_], a2: VertexAttribute[_]): VertexAttribute[_] = {
    unifyAttributeT(a1, a2)
  }

  def unifyAttributes(
    as1: Iterable[(String, VertexAttribute[_])],
    as2: Iterable[(String, VertexAttribute[_])]): Map[String, VertexAttribute[_]] = {

    val m1 = as1.toMap
    val m2 = as2.toMap
    m1.keySet.union(m2.keySet)
      .map(k => k -> (m1.get(k) ++ m2.get(k)).reduce(unifyAttribute _))
      .toMap
  }

  def concat(eb1: EdgeBundle, eb2: EdgeBundle): EdgeBundle = {
    new graph_util.BundleChain(Seq(eb1, eb2)).getCompositeEdgeBundle._1
  }

  def const(eb: EdgeBundle, value: Double = 1.0): VertexAttribute[Double] = {
    graph_operations.AddConstantAttribute.run(eb.asVertexSet, value)
  }

  def newScalar(data: String): Scalar[String] = {
    val op = graph_operations.CreateStringScalar(data)
    op.result.created
  }

  def downloadLink(fn: Filename, name: String) = {
    val urlPath = java.net.URLEncoder.encode(fn.fullString, "utf-8")
    val urlName = java.net.URLEncoder.encode(name, "utf-8")
    val url = s"/download?path=$urlPath&name=$urlName"
    val quoted = '"' + url + '"'
    newScalar(s"<a href=$quoted>download</a>")
  }
}
