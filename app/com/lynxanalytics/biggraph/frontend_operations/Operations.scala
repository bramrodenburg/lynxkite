// "Frontend" operations are all defined here.
//
// The code in this file defines the operation parameters to be offered on the UI,
// and also takes care of parsing the parameters given by the user and creating
// the "backend" operations and updating the projects.
package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.BigGraphEnvironment
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.graph_util.HadoopFile
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.controllers._
import play.api.libs.json

object OperationParams {
  case class Param(
      id: String,
      title: String,
      defaultValue: String = "",
      mandatory: Boolean = true) extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {}
  }
  case class Choice(
      id: String,
      title: String,
      options: List[UIValue],
      multipleChoice: Boolean = false) extends OperationParameterMeta {
    val kind = "choice"
    val defaultValue = ""
    val mandatory = true
    def validate(value: String): Unit = {
      val possibleValues = options.map { x => x.id }.toSet
      val givenValues = value.split(",", -1).toSet
      val unknown = givenValues -- possibleValues
      assert(unknown.isEmpty,
        s"Unknown option: ${unknown.mkString(", ")} (Possibilities: ${possibleValues.mkString(", ")})")
    }
  }
  case class TagList(
      id: String,
      title: String,
      options: List[UIValue],
      mandatory: Boolean = false) extends OperationParameterMeta {
    val kind = "tag-list"
    val multipleChoice = true
    val defaultValue = ""
    def validate(value: String): Unit = {}
  }
  case class File(id: String, title: String) extends OperationParameterMeta {
    val kind = "file"
    val multipleChoice = false
    val defaultValue = ""
    val options = List()
    val mandatory = true
    def validate(value: String): Unit = {}
  }
  case class Ratio(id: String, title: String, defaultValue: String = "")
      extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert((value matches """\d+(\.\d+)?""") && (value.toDouble <= 1.0),
        s"$title ($value) has to be a ratio, a double between 0.0 and 1.0")
    }
  }
  case class NonNegInt(id: String, title: String, default: Int)
      extends OperationParameterMeta {
    val kind = "default"
    val defaultValue = default.toString
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert(value matches """\d+""", s"$title ($value) has to be a non negative integer")
    }
  }
  case class NonNegDouble(id: String, title: String, defaultValue: String = "")
      extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert(value matches """\d+(\.\d+)?""", s"$title ($value) has to be a non negative double")
    }
  }
  case class Code(
      id: String,
      title: String,
      defaultValue: String = "",
      mandatory: Boolean = true) extends OperationParameterMeta {
    val kind = "code"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {}
  }

  // A random number to be used as default value for random seed parameters.
  case class RandomSeed(id: String, title: String) extends OperationParameterMeta {
    val defaultValue = util.Random.nextInt.toString
    val kind = "default"
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert(value matches """[+-]?\d+""", s"$title ($value) has to be an integer")
    }
  }
}

class Operations(env: BigGraphEnvironment) extends OperationRepository(env) {
  import Operation.Category
  import Operation.Context
  abstract class UtilityOperation(t: String, c: Context)
    extends Operation(t, c, Category("Utility operations", "green", icon = "wrench", sortKey = "zz"))
  trait SegOp extends Operation {
    protected def seg = project.asSegmentation
    protected def parent = seg.parent
    protected def segmentationParameters(): List[OperationParameterMeta]
    def parameters = {
      if (project.isSegmentation) segmentationParameters
      else List[OperationParameterMeta]()
    }
  }
  abstract class SegmentationUtilityOperation(t: String, c: Context)
    extends Operation(t, c, Category(
      "Segmentation utility operations",
      "green",
      visible = c.project.isSegmentation,
      icon = "wrench",
      sortKey = "zz")) with SegOp

  // Categories
  abstract class SpecialtyOperation(t: String, c: Context)
    extends Operation(t, c, Category("Specialty operations", "green", icon = "book"))

  abstract class EdgeAttributesOperation(t: String, c: Context)
    extends Operation(t, c, Category("Edge attribute operations", "blue", sortKey = "Attribute, edge"))

  abstract class VertexAttributesOperation(t: String, c: Context)
    extends Operation(t, c, Category("Vertex attribute operations", "blue", sortKey = "Attribute, vertex"))

  abstract class GlobalOperation(t: String, c: Context)
    extends Operation(t, c, Category("Global operations", "magenta", icon = "globe"))

  abstract class ExportOperation(t: String, c: Context)
    extends Operation(t, c, Category("Export operations", "yellow", icon = "export", sortKey = "IO, export"))

  abstract class ImportOperation(t: String, c: Context)
    extends Operation(t, c, Category("Import operations", "yellow", icon = "import", sortKey = "IO, import"))

  abstract class MetricsOperation(t: String, c: Context)
    extends Operation(t, c, Category("Graph metrics", "green", icon = "stats"))

  abstract class PropagationOperation(t: String, c: Context)
    extends Operation(t, c, Category("Propagation operations", "green", icon = "fullscreen"))

  abstract class HiddenOperation(t: String, c: Context)
    extends Operation(t, c, Category("Hidden operations", "black", visible = false))

  abstract class CreateSegmentationOperation(t: String, c: Context)
    extends Operation(t, c, Category(
      "Create segmentation",
      "green",
      icon = "th-large",
      visible = !c.project.isSegmentation))

  abstract class StructureOperation(t: String, c: Context)
    extends Operation(t, c, Category("Structure operations", "pink", icon = "asterisk"))

  import OperationParams._

  register("Discard vertices", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasVertexSet && isNotSegmentation
    def apply(params: Map[String, String]) = {
      project.vertexSet = null
    }
  })

  register("Discard edges", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      project.edgeBundle = null
    }
  })

  register("New vertex set", new StructureOperation(_, _) {
    def parameters = List(
      NonNegInt("size", "Vertex set size", default = 10))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val result = graph_operations.CreateVertexSet(params("size").toLong)().result
      project.setVertexSet(result.vs, idAttr = "id")
      project.newVertexAttribute("ordinal", result.ordinal)
    }
  })

  register("Create random edge bundle", new StructureOperation(_, _) {
    def parameters = List(
      NonNegDouble("degree", "Average degree", defaultValue = "10.0"),
      RandomSeed("seed", "Seed"))
    def enabled = hasVertexSet && hasNoEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.FastRandomEdgeBundle(
        params("seed").toInt, params("degree").toDouble)
      project.edgeBundle = op(op.vs, project.vertexSet).result.es
    }
  })

  register("Create scale-free random edge bundle", new StructureOperation(_, _) {
    def parameters = List(
      NonNegInt("iterations", "Number of iterations", default = 10),
      NonNegDouble(
        "perIterationMultiplier",
        "Per iteration edge number multiplier",
        defaultValue = "1.3"),
      RandomSeed("seed", "Seed"))
    def enabled = hasVertexSet && hasNoEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.ScaleFreeEdgeBundle(
        params("iterations").toInt,
        params("seed").toLong,
        params("perIterationMultiplier").toDouble)
      project.edgeBundle = op(op.vs, project.vertexSet).result.es
    }
  })

  register("Connect vertices on attribute", new StructureOperation(_, _) {
    def parameters = List(
      Choice("fromAttr", "Source attribute", options = vertexAttributes),
      Choice("toAttr", "Destination attribute", options = vertexAttributes))
    def enabled =
      (hasVertexSet && hasNoEdgeBundle
        && FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes."))
    private def applyAA[A](fromAttr: Attribute[A], toAttr: Attribute[A]) = {
      if (fromAttr == toAttr) {
        // Use the slightly faster operation.
        val op = graph_operations.EdgesFromAttributeMatches[A]()
        project.edgeBundle = op(op.attr, fromAttr).result.edges
      } else {
        val op = graph_operations.EdgesFromBipartiteAttributeMatches[A]()
        project.edgeBundle = op(op.fromAttr, fromAttr)(op.toAttr, toAttr).result.edges
      }
    }
    private def applyAB[A, B](fromAttr: Attribute[A], toAttr: Attribute[B]) = {
      applyAA(fromAttr, toAttr.asInstanceOf[Attribute[A]])
    }
    def apply(params: Map[String, String]) = {
      val fromAttrName = params("fromAttr")
      val toAttrName = params("toAttr")
      val fromAttr = project.vertexAttributes(fromAttrName)
      val toAttr = project.vertexAttributes(toAttrName)
      assert(fromAttr.typeTag.tpe =:= toAttr.typeTag.tpe,
        s"$fromAttrName and $toAttrName are not of the same type.")
      applyAB(fromAttr, toAttr)
    }
  })

  trait RowReader {
    def sourceParameters: List[OperationParameterMeta]
    def source(params: Map[String, String]): graph_operations.RowInput
  }

  trait CSVRowReader extends RowReader {
    def sourceParameters = List(
      File("files", "Files"),
      Param("header", "Header", defaultValue = "<read first line>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param("omitted", "(optional) Comma separated list of columns to omit"),
      Param("filter", "(optional) Filtering expression"),
      Choice("allow_corrupt_lines", "Tolerate ill-formed lines",
        options = UIValue.list(List("no", "yes"))))

    def source(params: Map[String, String]) = {
      val files = HadoopFile(params("files"))
      val header = if (params("header") == "<read first line>")
        graph_operations.ImportUtil.header(files) else params("header")

      val allowCorruptLines = params("allow_corrupt_lines") == "yes"

      graph_operations.CSV(
        files,
        params("delimiter"),
        header,
        params("omitted").split(",").map(_.trim).filter(_.nonEmpty).toSet,
        JavaScript(params("filter")),
        allowCorruptLines)
    }
  }

  trait SQLRowReader extends RowReader {
    def sourceParameters = List(
      Param("db", "Database"),
      Param("table", "Table or view"),
      Param("columns", "Columns"),
      Param("key", "Key column"))
    def source(params: Map[String, String]) = {
      val columns = params("columns").split(",", -1).map(_.trim).filter(_.nonEmpty)
      graph_operations.DBTable(
        params("db"),
        params("table"),
        (columns.toSet + params("key")).toSeq, // Always include "key".
        params("key"))
    }
  }

  abstract class ImportVerticesOperation(t: String, c: Context)
      extends ImportOperation(t, c) with RowReader {
    def parameters = sourceParameters ++ List(
      Param("id-attr", "ID attribute name", defaultValue = "id"))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val imp = graph_operations.ImportVertexList(source(params))().result
      project.vertexSet = imp.vertices
      for ((name, attr) <- imp.attrs) {
        project.newVertexAttribute(name, attr.entity, "imported")
      }
      val idAttr = params("id-attr")
      assert(
        !project.vertexAttributes.contains(idAttr),
        s"The input also contains a field called '$idAttr'. Please pick a different name.")
      project.newVertexAttribute(idAttr, idAsAttribute(project.vertexSet), "internal")
    }
  }
  register("Import vertices from CSV files",
    new ImportVerticesOperation(_, _) with CSVRowReader)
  register("Import vertices from a database",
    new ImportVerticesOperation(_, _) with SQLRowReader)

  abstract class ImportEdgesForExistingVerticesOperation(t: String, c: Context)
      extends ImportOperation(t, c) with RowReader {
    def parameters = sourceParameters ++ List(
      Choice("attr", "Vertex ID attribute",
        options = UIValue("!unset", "") +: vertexAttributes[String]),
      Param("src", "Source ID field"),
      Param("dst", "Destination ID field"))
    def enabled =
      hasNoEdgeBundle &&
        hasVertexSet &&
        FEStatus.assert(vertexAttributes[String].nonEmpty, "No vertex attributes to use as id.")
    def apply(params: Map[String, String]) = {
      val src = params("src")
      val dst = params("dst")
      assert(src.nonEmpty, "The Source ID field parameter must be set.")
      assert(dst.nonEmpty, "The Destination ID field parameter must be set.")
      val attrName = params("attr")
      assert(attrName != "!unset", "The Vertex ID attribute parameter must be set.")
      val attr = project.vertexAttributes(attrName).runtimeSafeCast[String]
      val op = graph_operations.ImportEdgeListForExistingVertexSet(source(params), src, dst)
      val imp = op(op.srcVidAttr, attr)(op.dstVidAttr, attr).result
      project.edgeBundle = imp.edges
      project.edgeAttributes = imp.attrs.mapValues(_.entity)
    }
  }
  register("Import edges for existing vertices from CSV files",
    new ImportEdgesForExistingVerticesOperation(_, _) with CSVRowReader)
  register("Import edges for existing vertices from a database",
    new ImportEdgesForExistingVerticesOperation(_, _) with SQLRowReader)

  abstract class ImportVerticesAndEdgesOperation(t: String, c: Context)
      extends ImportOperation(t, c) with RowReader {
    def parameters = sourceParameters ++ List(
      Param("src", "Source ID field"),
      Param("dst", "Destination ID field"))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val src = params("src")
      val dst = params("dst")
      assert(src.nonEmpty, "The Source ID field parameter must be set.")
      assert(dst.nonEmpty, "The Destination ID field parameter must be set.")
      val imp = graph_operations.ImportEdgeList(source(params), src, dst)().result
      project.setVertexSet(imp.vertices, idAttr = "id")
      project.newVertexAttribute("stringID", imp.stringID)
      project.edgeBundle = imp.edges
      project.edgeAttributes = imp.attrs.mapValues(_.entity)
    }
  }
  register("Import vertices and edges from single CSV fileset",
    new ImportVerticesAndEdgesOperation(_, _) with CSVRowReader)
  register("Import vertices and edges from single database table",
    new ImportVerticesAndEdgesOperation(_, _) with SQLRowReader)

  register("Convert vertices into edges", new StructureOperation(_, _) {
    def parameters = List(
      Choice("src", "Source", options = vertexAttributes[String]),
      Choice("dst", "Destination", options = vertexAttributes[String]))
    def enabled = hasNoEdgeBundle &&
      FEStatus.assert(vertexAttributes[String].size > 2, "Two string attributes are needed.")
    def apply(params: Map[String, String]) = {
      val srcAttr = project.vertexAttributes(params("src")).runtimeSafeCast[String]
      val dstAttr = project.vertexAttributes(params("dst")).runtimeSafeCast[String]
      val newGraph = {
        val op = graph_operations.VerticesToEdges()
        op(op.srcAttr, srcAttr)(op.dstAttr, dstAttr).result
      }
      val oldAttrs = project.vertexAttributes.toMap
      project.vertexSet = newGraph.vs
      project.edgeBundle = newGraph.es
      project.newVertexAttribute("stringID", newGraph.stringID)
      for ((name, attr) <- oldAttrs) {
        project.edgeAttributes(name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(
            attr, newGraph.embedding)
      }
    }
  })

  abstract class ImportVertexAttributesOperation(t: String, c: Context)
      extends ImportOperation(t, c) with RowReader {
    def parameters = sourceParameters ++ List(
      Choice("id-attr", "Vertex ID attribute",
        options = UIValue("!unset", "") +: vertexAttributes[String]),
      Param("id-field", "ID field"),
      Param("prefix", "Name prefix for the imported vertex attributes"))
    def enabled =
      hasVertexSet &&
        FEStatus.assert(vertexAttributes[String].nonEmpty, "No vertex attributes to use as id.")
    def apply(params: Map[String, String]) = {
      val attrName = params("id-attr")
      assert(attrName != "!unset", "The Vertex ID attribute parameter must be set.")
      val idAttr = project.vertexAttributes(attrName).runtimeSafeCast[String]
      val op = graph_operations.ImportAttributesForExistingVertexSet(source(params), params("id-field"))
      val res = op(op.idAttr, idAttr).result
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- res.attrs) {
        project.newVertexAttribute(prefix + name, attr, "imported")
      }
    }
  }
  register("Import vertex attributes from CSV files",
    new ImportVertexAttributesOperation(_, _) with CSVRowReader)
  register("Import vertex attributes from a database",
    new ImportVertexAttributesOperation(_, _) with SQLRowReader)

  abstract class ImportEdgeAttributesOperation(t: String, c: Context)
      extends ImportOperation(t, c) with RowReader {
    def parameters = sourceParameters ++ List(
      Choice("id-attr", "Edge ID attribute",
        options = UIValue("!unset", "") +: edgeAttributes[String]),
      Param("id-field", "ID field"),
      Param("prefix", "Name prefix for the imported edge attributes"))
    def enabled =
      hasEdgeBundle &&
        FEStatus.assert(edgeAttributes[String].nonEmpty, "No edge attributes to use as id.")
    def apply(params: Map[String, String]) = {
      val fieldName = params("id-field")
      assert(fieldName.nonEmpty, "The ID field parameter must be set.")
      val attrName = params("attr")
      assert(attrName != "!unset", "The Edge ID attribute parameter must be set.")
      val idAttr = project.edgeAttributes(attrName).runtimeSafeCast[String]
      val op = graph_operations.ImportAttributesForExistingVertexSet(source(params), fieldName)
      val res = op(op.idAttr, idAttr).result
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- res.attrs) {
        project.edgeAttributes(prefix + name) = attr
      }
    }
  }
  register("Import edge attributes from CSV files",
    new ImportEdgeAttributesOperation(_, _) with CSVRowReader)
  register("Import edge attributes from a database",
    new ImportEdgeAttributesOperation(_, _) with SQLRowReader)

  register("Maximal cliques", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "maximal_cliques"),
      Choice("bothdir", "Edges required in both directions", options = UIValue.list(List("true", "false"))),
      NonNegInt("min", "Minimum clique size", default = 3))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.FindMaxCliques(params("min").toInt, params("bothdir").toBoolean)
      val result = op(op.es, project.edgeBundle).result
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(result.segments, idAttr = "id")
      segmentation.notes = title
      segmentation.belongsTo = result.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
    }
  })

  register("Check cliques", new SegmentationUtilityOperation(_, _) {
    def segmentationParameters = List(
      Param("selected", "Segment IDs to check", defaultValue = "<All>"),
      Choice("bothdir", "Edges required in both directions", options = UIValue.list(List("true", "false"))))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      val selected =
        if (params("selected") == "<All>") None
        else Some(params("selected").split(",", -1).map(_.toLong).toSet)
      val op = graph_operations.CheckClique(selected, params("bothdir").toBoolean)
      val result = op(op.es, parent.edgeBundle)(op.belongsTo, seg.belongsTo).result
      parent.scalars("invalid_cliques") = result.invalid
    }
  })

  register("Connected components", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "connected_components"),
      Choice(
        "directions",
        "Edge direction",
        options = UIValue.list(List("ignore directions", "require both directions"))))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val symmetric = params("directions") match {
        case "ignore directions" => addReversed(project.edgeBundle)
        case "require both directions" => makeEdgeBundleSymmetric(project.edgeBundle)
      }
      val op = graph_operations.ConnectedComponents()
      val result = op(op.es, symmetric).result
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(result.segments, idAttr = "id")
      segmentation.notes = title
      segmentation.belongsTo = result.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
    }
  })

  register("Find infocom communities", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param(
        "cliques_name", "Name for maximal cliques segmentation", defaultValue = "maximal_cliques"),
      Param(
        "communities_name", "Name for communities segmentation", defaultValue = "communities"),
      Choice("bothdir", "Edges required in cliques in both directions", options = UIValue.list(List("true", "false"))),
      NonNegInt("min_cliques", "Minimum clique size", default = 3),
      Ratio("adjacency_threshold", "Adjacency threshold for clique overlaps", defaultValue = "0.6"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val cliquesResult = {
        val op = graph_operations.FindMaxCliques(
          params("min_cliques").toInt, params("bothdir").toBoolean)
        op(op.es, project.edgeBundle).result
      }

      val cliquesSegmentation = project.segmentation(params("cliques_name"))
      cliquesSegmentation.setVertexSet(cliquesResult.segments, idAttr = "id")
      cliquesSegmentation.notes = "Maximal cliques"
      cliquesSegmentation.belongsTo = cliquesResult.belongsTo
      cliquesSegmentation.newVertexAttribute("size", computeSegmentSizes(cliquesSegmentation))

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
      communitiesSegmentation.setVertexSet(ccResult.segments, idAttr = "id")
      communitiesSegmentation.notes = "Infocom Communities"
      communitiesSegmentation.belongsTo = vertexToCommunity
      communitiesSegmentation.newVertexAttribute(
        "size", computeSegmentSizes(communitiesSegmentation))
    }
  })

  register("Modular clustering", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "modular_clusters"),
      Choice("weights", "Weight attribute", options =
        UIValue("!no weight", "no weight") +: edgeAttributes[Double]),
      Param(
        "max-iterations",
        "Maximum number of iterations to do",
        defaultValue = "30"),
      Param(
        "min-increment-per-iteration",
        "Minimal modularity increment in an iteration to keep going",
        defaultValue = "0.001"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val edgeBundle = project.edgeBundle
      val weightsName = params("weights")
      val weights =
        if (weightsName == "!no weight") const(edgeBundle)
        else project.edgeAttributes(weightsName).runtimeSafeCast[Double]
      val result = {
        val op = graph_operations.FindModularClusteringByTweaks(
          params("max-iterations").toInt, params("min-increment-per-iteration").toDouble)
        op(op.edges, edgeBundle)(op.weights, weights).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(result.clusters, idAttr = "id")
      segmentation.notes = title
      segmentation.belongsTo = result.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))

      val symmetricDirection = Direction("all edges", project.edgeBundle)
      val symmetricEdges = symmetricDirection.edgeBundle
      val symmetricWeights = symmetricDirection.pull(weights)
      val modularity = {
        val op = graph_operations.Modularity()
        op(op.edges, symmetricEdges)(op.weights, symmetricWeights)(op.belongsTo, result.belongsTo)
          .result.modularity
      }
      segmentation.scalars("modularity") = modularity
    }
  })

  register("Segment by double attribute", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "bucketing"),
      Choice("attr", "Attribute", options = vertexAttributes[Double]),
      NonNegDouble("interval-size", "Interval size"),
      Choice("overlap", "Overlap", options = UIValue.list(List("no", "yes"))))
    def enabled = FEStatus.assert(vertexAttributes[Double].nonEmpty, "No double vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val attrName = params("attr")
      val overlap = params("overlap") == "yes"
      s"Segmentation by $attrName" + (if (overlap) " with overlap" else "")
    }

    def apply(params: Map[String, String]) = {
      val attrName = params("attr")
      val attr = project.vertexAttributes(attrName).runtimeSafeCast[Double]
      val overlap = params("overlap") == "yes"
      val intervalSize = params("interval-size").toDouble
      val bucketing = {
        val op = graph_operations.DoubleBucketing(intervalSize, overlap)
        op(op.attr, attr).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(bucketing.segments, idAttr = "id")
      segmentation.notes = summary(params)
      segmentation.belongsTo = bucketing.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
      segmentation.newVertexAttribute("bottom", bucketing.bottom)
      segmentation.newVertexAttribute("top", bucketing.top)
    }
  })

  register("Segment by string attribute", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "bucketing"),
      Choice("attr", "Attribute", options = vertexAttributes[String]))
    def enabled = FEStatus.assert(vertexAttributes[String].nonEmpty, "No string vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val attrName = params("attr")
      s"Segmentation by $attrName"
    }

    def apply(params: Map[String, String]) = {
      val attrName = params("attr")
      val attr = project.vertexAttributes(attrName).runtimeSafeCast[String]
      val bucketing = {
        val op = graph_operations.StringBucketing()
        op(op.attr, attr).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(bucketing.segments, idAttr = "id")
      segmentation.notes = summary(params)
      segmentation.belongsTo = bucketing.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
      segmentation.newVertexAttribute(attrName, bucketing.label)
    }
  })

  register("Segment by interval", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "bucketing"),
      Choice("begin_attr", "Begin attribute", options = vertexAttributes[Double]),
      Choice("end_attr", "End attribute", options = vertexAttributes[Double]),
      NonNegDouble("interval_size", "Interval size"),
      Choice("overlap", "Overlap", options = UIValue.list(List("no", "yes"))))
    def enabled = FEStatus.assert(
      vertexAttributes[Double].size >= 2,
      "Less than two double vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val beginAttrName = params("begin_attr")
      val endAttrName = params("end_attr")
      val overlap = params("overlap") == "yes"
      s"Interval segmentation by $beginAttrName and $endAttrName" + (if (overlap) " with overlap" else "")
    }

    def apply(params: Map[String, String]) = {
      val beginAttrName = params("begin_attr")
      val endAttrName = params("end_attr")
      val beginAttr = project.vertexAttributes(beginAttrName).runtimeSafeCast[Double]
      val endAttr = project.vertexAttributes(endAttrName).runtimeSafeCast[Double]
      val overlap = params("overlap") == "yes"
      val intervalSize = params("interval_size").toDouble
      val bucketing = {
        val op = graph_operations.IntervalBucketing(intervalSize, overlap)
        op(op.beginAttr, beginAttr)(op.endAttr, endAttr).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(bucketing.segments, idAttr = "id")
      segmentation.notes = summary(params)
      segmentation.belongsTo = bucketing.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
      segmentation.newVertexAttribute("bottom", bucketing.bottom)
      segmentation.newVertexAttribute("top", bucketing.top)
    }
  })

  register("Combine segmentations", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "New segmentation name"),
      Choice("segmentations", "Segmentations", options = segmentations, multipleChoice = true))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val segmentations = params("segmentations").replace(",", ", ")
      s"Combination of $segmentations"
    }

    def apply(params: Map[String, String]) = {
      val segmentations = params("segmentations").split(",").map(project.segmentation(_))
      assert(segmentations.size >= 2, "Please select at least 2 segmentations to combine.")
      val result = project.segmentation(params("name"))
      // Start by copying the first segmentation.
      val first = segmentations.head
      result.vertexSet = first.vertexSet;
      result.notes = summary(params)
      result.belongsTo = first.belongsTo
      for ((name, attr) <- first.vertexAttributes) {
        result.newVertexAttribute(
          s"${first.segmentationName}_$name", attr)
      }
      // Then combine the other segmentations one by one.
      for (seg <- segmentations.tail) {
        val combination = {
          val op = graph_operations.CombineSegmentations()
          op(op.belongsTo1, result.belongsTo)(op.belongsTo2, seg.belongsTo).result
        }
        val attrs = result.vertexAttributes.toMap
        result.vertexSet = combination.segments
        result.belongsTo = combination.belongsTo
        for ((name, attr) <- attrs) {
          // These names are already prefixed.
          result.vertexAttributes(name) =
            graph_operations.PulledOverVertexAttribute.pullAttributeVia(
              attr, combination.origin1)
        }
        for ((name, attr) <- seg.vertexAttributes) {
          // Add prefix for the new attributes.
          result.newVertexAttribute(
            s"${seg.segmentationName}_$name",
            graph_operations.PulledOverVertexAttribute.pullAttributeVia(
              attr, combination.origin2))
        }
      }
      // Calculate sizes and ids at the end.
      result.newVertexAttribute("size", computeSegmentSizes(result))
      result.newVertexAttribute("id", idAsAttribute(result.vertexSet))
    }
  })
  register("Internal vertex ID as attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "id"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      project.newVertexAttribute(params("name"), idAsAttribute(project.vertexSet), help)
    }
  })

  def idAsAttribute(vs: VertexSet) = {
    graph_operations.IdAsAttribute.run(vs)
  }

  register("Add gaussian vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "random"),
      RandomSeed("seed", "Seed"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.AddGaussianVertexAttribute(params("seed").toInt)
      project.newVertexAttribute(
        params("name"), op(op.vertices, project.vertexSet).result.attr, help)
    }
  })

  register("Add constant edge attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "weight"),
      Param("value", "Value", defaultValue = "1"),
      Choice("type", "Type", options = UIValue.list(List("Double", "String"))))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val res = {
        if (params("type") == "Double") {
          const(project.edgeBundle, params("value").toDouble)
        } else {
          graph_operations.AddConstantAttribute.run(project.edgeBundle.idSet, params("value"))
        }
      }
      project.edgeAttributes(params("name")) = res
    }
  })

  register("Add constant vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name"),
      Param("value", "Value", defaultValue = "1"),
      Choice("type", "Type", options = UIValue.list(List("Double", "String"))))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val value = params("value")
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = (params("type") == "Double"), value)
      project.newVertexAttribute(
        params("name"), op(op.vs, project.vertexSet).result.attr, s"constant $value")
    }
  })

  register("Fill with constant default value", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Vertex attribute", options = vertexAttributes[String] ++ vertexAttributes[Double]),
      Param("def", "Default value"))
    def enabled = FEStatus.assert(
      (vertexAttributes[String] ++ vertexAttributes[Double]).nonEmpty, "No vertex attributes.")
    override def title = "Fill vertex attribute with constant default value"
    def apply(params: Map[String, String]) = {
      val attr = project.vertexAttributes(params("attr"))
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = attr.is[Double], params("def"))
      val default = op(op.vs, project.vertexSet).result
      project.vertexAttributes(params("attr")) = unifyAttribute(attr, default.attr.entity)
    }
  })

  register("Fill edge attribute with constant default value", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Edge attribute", options = edgeAttributes[String] ++ edgeAttributes[Double]),
      Param("def", "Default value"))
    def enabled = FEStatus.assert(
      (edgeAttributes[String] ++ edgeAttributes[Double]).nonEmpty, "No edge attributes.")
    def apply(params: Map[String, String]) = {
      val attr = project.edgeAttributes(params("attr"))
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = attr.is[Double], params("def"))
      val default = op(op.vs, project.edgeBundle.idSet).result
      project.edgeAttributes(params("attr")) = unifyAttribute(attr, default.attr.entity)
    }
  })

  register("Merge two attributes", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "New attribute name", defaultValue = ""),
      Choice("attr1", "Primary attribute", options = vertexAttributes),
      Choice("attr2", "Secondary attribute", options = vertexAttributes))
    def enabled = FEStatus.assert(
      vertexAttributes.size >= 2, "Not enough vertex attributes.")
    override def title = "Merge two vertex attributes"
    def apply(params: Map[String, String]) = {
      val name = params("name")
      assert(name.nonEmpty, "You must specify a name for the new attribute.")
      val attr1 = project.vertexAttributes(params("attr1"))
      val attr2 = project.vertexAttributes(params("attr2"))
      assert(attr1.typeTag.tpe =:= attr2.typeTag.tpe,
        "The two attributes must have the same type.")
      project.newVertexAttribute(name, unifyAttribute(attr1, attr2))
    }
  })

  register("Merge two edge attributes", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "New attribute name", defaultValue = ""),
      Choice("attr1", "Primary attribute", options = edgeAttributes),
      Choice("attr2", "Secondary attribute", options = edgeAttributes))
    def enabled = FEStatus.assert(
      edgeAttributes.size >= 2, "Not enough edge attributes.")
    def apply(params: Map[String, String]) = {
      val name = params("name")
      assert(name.nonEmpty, "You must specify a name for the new attribute.")
      val attr1 = project.edgeAttributes(params("attr1"))
      val attr2 = project.edgeAttributes(params("attr2"))
      assert(attr1.typeTag.tpe =:= attr2.typeTag.tpe,
        "The two attributes must have the same type.")
      project.edgeAttributes(name) = unifyAttribute(attr1, attr2)
    }
  })

  register("Reverse edge direction", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.ReverseEdges()
      val res = op(op.esAB, project.edgeBundle).result
      project.pullBackEdges(
        project.edgeBundle,
        project.edgeAttributes.toIndexedSeq,
        res.esBA,
        res.injection)
    }
  })

  register("Add reversed edges", new StructureOperation(_, _) {
    def parameters = List(
      Param("distattr", "Distinguishing edge attribute")
    )
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val rev = {
        val op = graph_operations.AddReversedEdges()
        op(op.es, project.edgeBundle).result
      }

      project.pullBackEdges(
        project.edgeBundle,
        project.edgeAttributes.toIndexedSeq,
        rev.esPlus,
        rev.newToOriginal)
      if (params("distattr").nonEmpty) {
        project.edgeAttributes(params("distattr")) = rev.isNew
      }
    }
  })

  register("Clustering coefficient", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "clustering_coefficient"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.ClusteringCoefficient()
      project.newVertexAttribute(
        params("name"), op(op.es, project.edgeBundle).result.clustering, help)
    }
  })

  register("Embeddedness", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "embeddedness"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.Embeddedness()
      project.edgeAttributes(params("name")) = op(op.es, project.edgeBundle).result.embeddedness
    }
  })

  register("Dispersion", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "dispersion"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val dispersion = {
        val op = graph_operations.Dispersion()
        op(op.es, project.edgeBundle).result.dispersion.entity
      }
      val embeddedness = {
        val op = graph_operations.Embeddedness()
        op(op.es, project.edgeBundle).result.embeddedness.entity
      }
      // http://arxiv.org/pdf/1310.6753v1.pdf
      var normalizedDispersion = {
        val op = graph_operations.DeriveJSDouble(
          JavaScript("Math.pow(disp, 0.61) / (emb + 5)"),
          Seq("disp", "emb"))
        op(op.attrs, graph_operations.VertexAttributeToJSValue.seq(
          dispersion, embeddedness)).result.attr.entity
      }
      // TODO: recursive dispersion
      project.edgeAttributes(params("name")) = dispersion
      project.edgeAttributes("normalized_" + params("name")) = normalizedDispersion
    }
  })

  register("Degree", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "degree"),
      Choice("direction", "Count", options = Direction.options))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val es = Direction(params("direction"), project.edgeBundle, reversed = true).edgeBundle
      val op = graph_operations.OutDegree()
      project.newVertexAttribute(
        params("name"), op(op.es, es).result.outDegree, params("direction") + help)
    }
  })

  register("PageRank", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "page_rank"),
      Choice("weights", "Weight attribute",
        options = UIValue("!no weight", "no weight") +: edgeAttributes[Double]),
      NonNegInt("iterations", "Number of iterations", default = 5),
      Ratio("damping", "Damping factor", defaultValue = "0.85"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.PageRank(params("damping").toDouble, params("iterations").toInt)
      val weights =
        if (params("weights") == "!no weight") const(project.edgeBundle)
        else project.edgeAttributes(params("weights")).runtimeSafeCast[Double]
      project.newVertexAttribute(
        params("name"), op(op.es, project.edgeBundle)(op.weights, weights).result.pagerank, help)
    }
  })

  register("Shortest path", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "shortest_distance"),
      Choice("edge_distance", "Edge distance attribute",
        options = UIValue("!unit distances", "unit distances") +: edgeAttributes[Double]),
      Choice("starting_distance", "Starting distance attribute", options = vertexAttributes[Double]),
      NonNegInt("iterations", "Maximum number of iterations", default = 10)
    )
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val startingDistanceAttr = params("starting_distance")
      val startingDistance = project
        .vertexAttributes(startingDistanceAttr)
        .runtimeSafeCast[Double]
      val op = graph_operations.ShortestPath(params("iterations").toInt)
      val edgeDistance =
        if (params("edge_distance") == "!unit distances") {
          const(project.edgeBundle)
        } else {
          project.edgeAttributes(params("edge_distance")).runtimeSafeCast[Double]
        }
      project.newVertexAttribute(
        params("name"),
        op(op.es, project.edgeBundle)(op.edgeDistance, edgeDistance)(op.startingDistance, startingDistance).result.distance, help)
    }
  })

  register("Centrality", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "centrality"),
      NonNegInt("maxDiameter", "Maximal diameter to check", default = 10),
      Choice("algorithm", "Centrality type", options = UIValue.list(List("Harmonic", "Lin"))))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val name = params("name")
      val algorithm = params("algorithm")
      assert(name.nonEmpty, "Please set an attribute name.")
      val op = graph_operations.HyperBallCentrality(params("maxDiameter").toInt, algorithm)
      project.newVertexAttribute(
        name, op(op.es, project.edgeBundle).result.centrality, algorithm + help)
    }
  })

  register("Add rank attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("rankattr", "Rank attribute name", defaultValue = "ranking"),
      Choice("keyattr", "Key attribute name", options = vertexAttributes[Double]),
      Choice("order", "Order", options = UIValue.list(List("ascending", "descending"))))

    def enabled = FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric (double) vertex attributes")
    def apply(params: Map[String, String]) = {
      val keyAttr = params("keyattr")
      val rankAttr = params("rankattr")
      val ascending = params("order") == "ascending"
      assert(keyAttr.nonEmpty, "Please set a key attribute name.")
      assert(rankAttr.nonEmpty, "Please set a name for the rank attribute")
      val op = graph_operations.AddRankingAttributeDouble(ascending)
      val sortKey = project.vertexAttributes(keyAttr).runtimeSafeCast[Double]
      project.newVertexAttribute(
        rankAttr, toDouble(op(op.sortKey, sortKey).result.ordinal), s"rank by $keyAttr" + help)
    }
  })

  register("Example Graph", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val g = graph_operations.ExampleGraph()().result
      project.vertexSet = g.vertices
      project.edgeBundle = g.edges
      for ((name, attr) <- g.vertexAttributes) {
        project.newVertexAttribute(name, attr)
      }
      project.newVertexAttribute("id", idAsAttribute(project.vertexSet))
      project.edgeAttributes = g.edgeAttributes.mapValues(_.entity)
      for ((name, s) <- g.scalars) {
        project.scalars(name) = s.entity
      }
    }
  })

  register("Enhanced Example Graph", new HiddenOperation(_, _) {
    def parameters = List()
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val g = graph_operations.EnhancedExampleGraph()().result
      project.vertexSet = g.vertices
      project.edgeBundle = g.edges
      for ((name, attr) <- g.vertexAttributes) {
        project.newVertexAttribute(name, attr)
      }
      project.newVertexAttribute("id", idAsAttribute(project.vertexSet))
      project.edgeAttributes = g.edgeAttributes.mapValues(_.entity)
    }
  })

  private val toStringHelpText = "Converts the selected %s attributes to string type."
  register("Vertex attribute to string", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Vertex attribute", options = vertexAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes.")
    def apply(params: Map[String, String]) = {
      for (attr <- params("attr").split(",", -1)) {
        project.vertexAttributes(attr) = attributeToString(project.vertexAttributes(attr))
      }
    }
  })

  register("Edge attribute to string", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Edge attribute", options = edgeAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes.")
    def apply(params: Map[String, String]) = {
      for (attr <- params("attr").split(",", -1)) {
        project.edgeAttributes(attr) = attributeToString(project.edgeAttributes(attr))
      }
    }
  })

  private val toDoubleHelpText =
    """Converts the selected string typed %s attributes to double (double precision floating point
    number) type.
    """
  register("Vertex attribute to double", new VertexAttributesOperation(_, _) {
    val eligible = vertexAttributes[String] ++ vertexAttributes[Long]
    def parameters = List(
      Choice("attr", "Vertex attribute", options = eligible, multipleChoice = true))
    def enabled = FEStatus.assert(eligible.nonEmpty, "No eligible vertex attributes.")
    def apply(params: Map[String, String]) = {
      for (name <- params("attr").split(",", -1)) {
        val attr = project.vertexAttributes(name)
        project.vertexAttributes(name) = toDouble(attr)
      }
    }
  })

  register("Edge attribute to double", new EdgeAttributesOperation(_, _) {
    val eligible = edgeAttributes[String] ++ edgeAttributes[Long]
    def parameters = List(
      Choice("attr", "Edge attribute", options = eligible, multipleChoice = true))
    def enabled = FEStatus.assert(eligible.nonEmpty, "No eligible edge attributes.")
    def apply(params: Map[String, String]) = {
      for (name <- params("attr").split(",", -1)) {
        val attr = project.edgeAttributes(name)
        project.edgeAttributes(name) = toDouble(attr)
      }
    }
  })

  register("Vertex attributes to position", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("output", "Save as", defaultValue = "position"),
      Choice("x", "X or latitude", options = vertexAttributes[Double]),
      Choice("y", "Y or longitude", options = vertexAttributes[Double]))
    def enabled = FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes.")
    def apply(params: Map[String, String]) = {
      assert(params("output").nonEmpty, "Please set an attribute name.")
      val pos = {
        val op = graph_operations.JoinAttributes[Double, Double]()
        val x = project.vertexAttributes(params("x")).runtimeSafeCast[Double]
        val y = project.vertexAttributes(params("y")).runtimeSafeCast[Double]
        op(op.a, x)(op.b, y).result.attr
      }
      project.vertexAttributes(params("output")) = pos
    }
  })

  register("Edge graph", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.EdgeGraph()
      val g = op(op.es, project.edgeBundle).result
      project.setVertexSet(g.newVS, idAttr = "id")
      project.edgeBundle = g.newES
    }
  })

  register("Derived vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("output", "Save as"),
      Choice("type", "Result type", options = UIValue.list(List("double", "string"))),
      Code("expr", "Value", defaultValue = "1"))
    def enabled = hasVertexSet
    override def summary(params: Map[String, String]) = {
      val name = params("output")
      s"Derived vertex attribute ($name)"
    }
    def apply(params: Map[String, String]) = {
      assert(params("output").nonEmpty, "Please set an output attribute name.")
      val expr = params("expr")
      val vertexSet = project.vertexSet
      val namedAttributes = project.vertexAttributes
        .filter { case (name, attr) => containsIdentifierJS(expr, name) }
        .toIndexedSeq
      val result = params("type") match {
        case "string" =>
          graph_operations.DeriveJS.deriveFromAttributes[String](expr, namedAttributes, vertexSet)
        case "double" =>
          graph_operations.DeriveJS.deriveFromAttributes[Double](expr, namedAttributes, vertexSet)
      }
      project.newVertexAttribute(params("output"), result.attr, expr + help)
    }
  })

  register("Derived edge attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("output", "Save as"),
      Choice("type", "Result type", options = UIValue.list(List("double", "string"))),
      Code("expr", "Value", defaultValue = "1"))
    def enabled = hasEdgeBundle
    override def summary(params: Map[String, String]) = {
      val name = params("output")
      s"Derived edge attribute ($name)"
    }
    def apply(params: Map[String, String]) = {
      val expr = params("expr")
      val edgeBundle = project.edgeBundle
      val idSet = project.edgeBundle.idSet
      val namedEdgeAttributes = project.edgeAttributes
        .filter { case (name, attr) => containsIdentifierJS(expr, name) }
        .toIndexedSeq
      val namedSrcVertexAttributes = project.vertexAttributes
        .filter { case (name, attr) => containsIdentifierJS(expr, "src$" + name) }
        .toIndexedSeq
        .map {
          case (name, attr) =>
            "src$" + name -> graph_operations.VertexToEdgeAttribute.srcAttribute(attr, edgeBundle)
        }
      val namedDstVertexAttributes = project.vertexAttributes
        .filter { case (name, attr) => containsIdentifierJS(expr, "dst$" + name) }
        .toIndexedSeq
        .map {
          case (name, attr) =>
            "dst$" + name -> graph_operations.VertexToEdgeAttribute.dstAttribute(attr, edgeBundle)
        }

      val namedAttributes =
        namedEdgeAttributes ++ namedSrcVertexAttributes ++ namedDstVertexAttributes

      val result = params("type") match {
        case "string" =>
          graph_operations.DeriveJS.deriveFromAttributes[String](expr, namedAttributes, idSet)
        case "double" =>
          graph_operations.DeriveJS.deriveFromAttributes[Double](expr, namedAttributes, idSet)
      }
      project.edgeAttributes(params("output")) = result.attr
    }
  })

  register("Aggregate to segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = aggregateParams(parent.vertexAttributes)
    def enabled =
      isSegmentation &&
        FEStatus.assert(parent.vertexAttributes.nonEmpty,
          "No vertex attributes on parent")
    def apply(params: Map[String, String]) = {
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo,
          AttributeWithLocalAggregator(parent.vertexAttributes(attr), choice))
        project.newVertexAttribute(s"${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate to segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Choice("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(parent.vertexAttributes, weighted = true)
    def enabled =
      isSegmentation &&
        FEStatus.assert(parent.vertexAttributeNames[Double].nonEmpty,
          "No numeric vertex attributes on parent")
    def apply(params: Map[String, String]) = {
      val weightName = params("weight")
      val weight = parent.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo,
          AttributeWithWeightedAggregator(weight, parent.vertexAttributes(attr), choice))
        project.newVertexAttribute(s"${attr}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("Aggregate from segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Generated name prefix",
        defaultValue = project.asSegmentation.segmentationName)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      isSegmentation &&
        FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          reverse(seg.belongsTo),
          AttributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        seg.parent.newVertexAttribute(s"${prefix}${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate from segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Generated name prefix",
        defaultValue = project.asSegmentation.segmentationName),
      Choice("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(project.vertexAttributes, weighted = true)
    def enabled =
      isSegmentation &&
        FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          reverse(seg.belongsTo),
          AttributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
        seg.parent.newVertexAttribute(s"${prefix}${attr}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("Create edges from set overlaps", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List(
      NonNegInt("minOverlap", "Minimal overlap for connecting two segments", default = 3))
    def enabled = hasNoEdgeBundle && isSegmentation
    def apply(params: Map[String, String]) = {
      val op = graph_operations.SetOverlap(params("minOverlap").toInt)
      val res = op(op.belongsTo, seg.belongsTo).result
      project.edgeBundle = res.overlaps
      project.edgeAttributes("Overlap size") =
        // Long is better supported on the frontend.
        graph_operations.IntAttributeToLong.run(res.overlapSize)
    }
  })

  register("Create edges from co-occurrence", new StructureOperation(_, _) with SegOp {
    private def segmentationSizesSquareSum()(
      implicit manager: MetaGraphManager): Scalar[_] = {
      val size = aggregateViaConnection(
        seg.belongsTo,
        AttributeWithLocalAggregator(parent.vertexAttributes("id"), "count")
      )
      val sizeSquare: Attribute[Double] = {
        val op = graph_operations.DeriveJSDouble(
          JavaScript("size * size"),
          Seq("size"))
        op(
          op.attrs,
          graph_operations.VertexAttributeToJSValue.seq(size)).result.attr
      }
      aggregate(AttributeWithAggregator(sizeSquare, "sum"))
    }

    def segmentationParameters = List()
    override def visibleScalars =
      if (project.isSegmentation) {
        val scalar = segmentationSizesSquareSum()
        List(FEOperationScalarMeta("num_created_edges", scalar.gUID.toString))
      } else {
        List()
      }

    def enabled =
      isSegmentation &&
        FEStatus.assert(parent.edgeBundle == null, "Parent graph has edges already.")
    def apply(params: Map[String, String]) = {
      val op = graph_operations.EdgesFromSegmentation()
      val result = op(op.belongsTo, seg.belongsTo).result
      parent.edgeBundle = result.es
      for ((name, attr) <- project.vertexAttributes) {
        parent.edgeAttributes(s"${seg.segmentationName}_$name") =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(
            attr, result.origin)
      }
    }
  })

  register("Aggregate on neighbors", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Choice("direction", "Aggregate on", options = Direction.options)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes") && hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = Direction(params("direction"), project.edgeBundle).edgeBundle
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          edges,
          AttributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        project.newVertexAttribute(s"${prefix}${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate on neighbors", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Choice("weight", "Weight", options = vertexAttributes[Double]),
      Choice("direction", "Aggregate on", options = Direction.options)) ++
      aggregateParams(project.vertexAttributes, weighted = true)
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes") && hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = Direction(params("direction"), project.edgeBundle).edgeBundle
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((name, choice) <- parseAggregateParams(params)) {
        val attr = project.vertexAttributes(name)
        val result = aggregateViaConnection(
          edges,
          AttributeWithWeightedAggregator(weight, attr, choice))
        project.newVertexAttribute(s"${prefix}${name}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("Split vertices", new StructureOperation(_, _) {
    def parameters = List(
      Choice("rep", "Repetition attribute", options = vertexAttributes[Double]),
      Param("idattr", "ID attribute name", defaultValue = "new_id"),
      Param("idx", "Index attribute name", defaultValue = "index"))

    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No double vertex attributes")
    def doSplit(doubleAttr: Attribute[Double]): graph_operations.SplitVertices.Output = {
      val convOp = graph_operations.DoubleAttributeToLong
      val longAttr = convOp.run(doubleAttr)
      val op = graph_operations.SplitVertices()
      op(op.attr, longAttr).result
    }
    def apply(params: Map[String, String]) = {
      val rep = params("rep")

      val split = doSplit(project.vertexAttributes(rep).runtimeSafeCast[Double])

      project.pullBack(split.belongsTo)
      project.vertexAttributes(params("idx")) = split.indexAttr
      project.newVertexAttribute(params("idattr"), idAsAttribute(project.vertexSet))
    }
  })

  register("Merge vertices by attribute", new StructureOperation(_, _) {
    def parameters = List(
      Choice("key", "Match by", options = vertexAttributes)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def merge[T](attr: Attribute[T]): graph_operations.MergeVertices.Output = {
      val op = graph_operations.MergeVertices[T]()
      op(op.attr, attr).result
    }
    def apply(params: Map[String, String]) = {
      val key = params("key")
      val m = merge(project.vertexAttributes(key))
      val oldVAttrs = project.vertexAttributes.toMap
      val oldEdges = project.edgeBundle
      val oldEAttrs = project.edgeAttributes.toMap
      val oldSegmentations = project.viewer.segmentationMap
      project.setVertexSet(m.segments, idAttr = "id")
      for ((name, segViewer) <- oldSegmentations) {
        project.newSegmentation(name, segViewer.segmentationState)
        val seg = project.segmentation(name)
        val op = graph_operations.InducedEdgeBundle(induceDst = false)
        seg.belongsTo = op(
          op.srcMapping, m.belongsTo)(
            op.edges, seg.belongsTo).result.induced
      }
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          m.belongsTo,
          AttributeWithLocalAggregator(oldVAttrs(attr), choice))
        project.newVertexAttribute(s"${attr}_${choice}", result)
      }
      // Automatically keep the key attribute.
      project.vertexAttributes(key) = aggregateViaConnection(
        m.belongsTo,
        AttributeWithLocalAggregator(oldVAttrs(key), "most_common"))
      if (oldEdges != null) {
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
    }
  })

  private def mergeEdgesWithKey[T](edgesAsAttr: Attribute[(ID, ID)], keyAttr: Attribute[T]) = {
    val edgesAndKey: Attribute[((ID, ID), T)] = joinAttr(edgesAsAttr, keyAttr)
    val op = graph_operations.MergeVertices[((ID, ID), T)]()
    op(op.attr, edgesAndKey).result
  }

  private def mergeEdges(edgesAsAttr: Attribute[(ID, ID)]) = {
    val op = graph_operations.MergeVertices[(ID, ID)]()
    op(op.attr, edgesAsAttr).result
  }
  // Common code for operations "merge parallel edges" and "merge parallel edges by key"

  private def applyMergeParallelEdgesByKey(project: ProjectEditor, params: Map[String, String]) = {

    val edgesAsAttr = {
      val op = graph_operations.EdgeBundleAsAttribute()
      op(op.edges, project.edgeBundle).result.attr
    }

    val hasKeyAttr = params.contains("key")

    val mergedResult =
      if (hasKeyAttr) {
        val keyAttr = project.edgeAttributes(params("key"))
        mergeEdgesWithKey(edgesAsAttr, keyAttr)
      } else {
        mergeEdges(edgesAsAttr)
      }

    val newEdges = {
      val op = graph_operations.PulledOverEdges()
      op(op.originalEB, project.edgeBundle)(op.injection, mergedResult.representative)
        .result.pulledEB
    }
    val oldAttrs = project.edgeAttributes.toMap
    project.edgeBundle = newEdges

    for ((attr, choice) <- parseAggregateParams(params)) {
      project.edgeAttributes(s"${attr}_${choice}") =
        aggregateViaConnection(
          mergedResult.belongsTo,
          AttributeWithLocalAggregator(oldAttrs(attr), choice))
    }
    if (hasKeyAttr) {
      val key = params("key")
      project.edgeAttributes(key) =
        aggregateViaConnection(mergedResult.belongsTo,
          AttributeWithLocalAggregator(oldAttrs(key), "most_common"))
    }
  }

  register("Merge parallel edges", new StructureOperation(_, _) {
    def parameters = aggregateParams(project.edgeAttributes)
    def enabled = hasEdgeBundle

    def apply(params: Map[String, String]) = {
      applyMergeParallelEdgesByKey(project, params)
    }
  })

  register("Merge parallel edges by attribute", new StructureOperation(_, _) {
    def parameters = List(
      Choice("key", "Merge by", options = edgeAttributes)) ++
      aggregateParams(project.edgeAttributes)
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty,
      "There must be at least one edge attribute")

    def apply(params: Map[String, String]) = {
      applyMergeParallelEdgesByKey(project, params)
    }
  })

  register("Discard loop edges", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val edgesAsAttr = {
        val op = graph_operations.EdgeBundleAsAttribute()
        op(op.edges, project.edgeBundle).result.attr
      }
      val guid = edgesAsAttr.entity.gUID.toString
      val embedding = FEFilters.embedFilteredVertices(
        project.edgeBundle.idSet,
        Seq(FEVertexAttributeFilter(guid, "!=")))
      project.pullBackEdges(embedding)
    }
  })

  register("Aggregate vertex attribute globally", new GlobalOperation(_, _) {
    def parameters = List(Param("prefix", "Generated name prefix")) ++
      aggregateParams(project.vertexAttributes, needsGlobal = true)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(AttributeWithAggregator(project.vertexAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}"
        project.scalars(name) = result
      }
    }
  })

  register("Weighted aggregate vertex attribute globally", new GlobalOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix"),
      Choice("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(project.vertexAttributes, needsGlobal = true, weighted = true)
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          AttributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}_by_${weightName}"
        project.scalars(name) = result
      }
    }
  })

  register("Aggregate edge attribute globally", new GlobalOperation(_, _) {
    def parameters = List(Param("prefix", "Generated name prefix")) ++
      aggregateParams(
        project.edgeAttributes,
        needsGlobal = true)
    def enabled =
      FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          AttributeWithAggregator(project.edgeAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}"
        project.scalars(name) = result
      }
    }
  })

  register("Weighted aggregate edge attribute globally", new GlobalOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix"),
      Choice("weight", "Weight", options = edgeAttributes[Double])) ++
      aggregateParams(
        project.edgeAttributes,
        needsGlobal = true, weighted = true)
    def enabled =
      FEStatus.assert(edgeAttributes[Double].nonEmpty, "No numeric edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.edgeAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          AttributeWithWeightedAggregator(weight, project.edgeAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}_by_${weightName}"
        project.scalars(name) = result
      }
    }
  })

  register("Aggregate edge attribute to vertices", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "edge"),
      Choice("direction", "Aggregate on", options = Direction.attrOptions)) ++
      aggregateParams(
        project.edgeAttributes)
    def enabled =
      FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      val direction = Direction(params("direction"), project.edgeBundle)
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateFromEdges(
          direction.edgeBundle,
          AttributeWithLocalAggregator(
            direction.pull(project.edgeAttributes(attr)),
            choice))
        project.newVertexAttribute(s"${prefix}${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate edge attribute to vertices", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "edge"),
      Choice("weight", "Weight", options = edgeAttributes[Double]),
      Choice("direction", "Aggregate on", options = Direction.attrOptions)) ++
      aggregateParams(
        project.edgeAttributes,
        weighted = true)
    def enabled =
      FEStatus.assert(edgeAttributes[Double].nonEmpty, "No numeric edge attributes")
    def apply(params: Map[String, String]) = {
      val direction = Direction(params("direction"), project.edgeBundle)
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.edgeAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateFromEdges(
          direction.edgeBundle,
          AttributeWithWeightedAggregator(
            direction.pull(weight),
            direction.pull(project.edgeAttributes(attr)),
            choice))
        project.newVertexAttribute(s"${prefix}${attr}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("No operation", new UtilityOperation(_, _) {
    def parameters = List()
    def enabled = FEStatus.enabled
    def apply(params: Map[String, String]) = {}
  })

  register("Discard edge attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = edgeAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    override def title = "Discard edge attributes"
    override def summary(params: Map[String, String]) = {
      val names = params("name").replace(",", ", ")
      s"Discard edge attributes: $names"
    }
    def apply(params: Map[String, String]) = {
      for (param <- params("name").split(",", -1)) {
        project.deleteEdgeAttribute(param)
      }
    }
  })

  register("Discard vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = vertexAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    override def title = "Discard vertex attributes"
    override def summary(params: Map[String, String]) = {
      val names = params("name").replace(",", ", ")
      s"Discard vertex attributes: $names"
    }
    def apply(params: Map[String, String]) = {
      for (param <- params("name").split(",", -1)) {
        project.deleteVertexAttribute(param)
      }
    }
  })

  register("Discard segmentation", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = segmentations))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val name = params("name")
      s"Discard segmentation: $name"
    }
    def apply(params: Map[String, String]) = {
      project.deleteSegmentation(params("name"))
    }
  })

  register("Discard scalar", new GlobalOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = scalars, multipleChoice = true))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    override def title = "Discard scalars"
    override def summary(params: Map[String, String]) = {
      val names = params("name").replace(",", ", ")
      s"Discard scalars: $names"
    }
    def apply(params: Map[String, String]) = {
      for (param <- params("name").split(",", -1)) {
        project.deleteScalar(param)
      }
    }
  })

  register("Rename edge attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = edgeAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename edge attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(!project.edgeAttributes.contains(params("to")),
        s"""An edge-attribute named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.edgeAttributes(params("to")) = project.edgeAttributes(params("from"))
      project.edgeAttributes(params("from")) = null
    }
  })

  register("Rename vertex attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = vertexAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename vertex attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(!project.vertexAttributes.contains(params("to")),
        s"""A vertex-attribute named '${params("to")}' already exists,
            please discard it or choose another name""")
      assert(params("to").nonEmpty, "Please set the new attribute name.")
      project.newVertexAttribute(
        params("to"), project.vertexAttributes(params("from")),
        project.viewer.getVertexAttributeNote(params("from")))
      project.vertexAttributes(params("from")) = null
    }
  })

  register("Rename segmentation", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = segmentations),
      Param("to", "New name"))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename segmentation $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(
        !project.segmentationNames.contains(params("to")),
        s"""A segmentation named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.segmentation(params("to")).segmentationState =
        project.segmentation(params("from")).segmentationState
      project.deleteSegmentation(params("from"))
    }
  })

  register("Rename scalar", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = scalars),
      Param("to", "New name"))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename scalar $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(!project.scalars.contains(params("to")),
        s"""A scalar named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.scalars(params("to")) = project.scalars(params("from"))
      project.scalars(params("from")) = null
    }
  })

  register("Copy edge attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = edgeAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy edge attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      project.edgeAttributes(params("to")) = project.edgeAttributes(params("from"))
    }
  })

  register("Copy vertex attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = vertexAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy vertex attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(params("to").nonEmpty, "Please set the new attribute name.")
      project.newVertexAttribute(
        params("to"), project.vertexAttributes(params("from")),
        project.viewer.getVertexAttributeNote(params("from")))
    }
  })

  register("Copy segmentation", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = segmentations),
      Param("to", "New name"))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy segmentation $from to $to"
    }
    def apply(params: Map[String, String]) = {
      val from = project.segmentation(params("from"))
      val to = project.segmentation(params("to"))
      to.segmentationState = from.segmentationState
    }
  })

  register("Copy scalar", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = scalars),
      Param("to", "New name"))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy scalar $from to $to"
    }
    def apply(params: Map[String, String]) = {
      project.scalars(params("to")) = project.scalars(params("from"))
    }
  })

  register("Copy graph into a segmentation", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "self_as_segmentation"))
    def enabled = hasVertexSet

    def apply(params: Map[String, String]) = {
      val oldProjectState = project.state
      val segmentation = project.segmentation(params("name"))
      segmentation.state = oldProjectState

      val op = graph_operations.LoopEdgeBundle()
      segmentation.belongsTo = op(op.vs, project.vertexSet).result.eb
    }
  })

  register("Import project as segmentation", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Choice("them", "Other project's name", options = readableProjects))
    def enabled = hasVertexSet
    override def summary(params: Map[String, String]) = {
      val them = params("them")
      s"Import $them as segmentation"
    }
    def apply(params: Map[String, String]) = {
      val themName = params("them")
      assert(readableProjects.map(_.id).contains(themName), s"Unknown project: $themName")
      val them = ProjectFrame.fromName(themName).viewer
      assert(them.vertexSet != null, s"No vertex set in $them")
      val segmentation = project.segmentation(params("them"))
      segmentation.state = them.state
      val op = graph_operations.EmptyEdgeBundle()
      segmentation.belongsTo = op(op.src, project.vertexSet)(op.dst, them.vertexSet).result.eb
    }
  })

  abstract class LoadSegmentationLinksOperation(t: String, c: Context)
      extends ImportOperation(t, c) with RowReader with SegOp {
    def segmentationParameters = sourceParameters ++ List(
      Choice(
        "base-id-attr",
        s"Identifying vertex attribute in base project",
        options = UIValue.list(parent.vertexAttributeNames[String].toList)),
      Param("base-id-field", s"Identifying field for base project"),
      Choice(
        "seg-id-attr",
        s"Identifying vertex attribute in segmentation",
        options = vertexAttributes[String]),
      Param("seg-id-field", s"Identifying field for segmentation"))
    def enabled =
      isSegmentation &&
        FEStatus.assert(
          vertexAttributes[String].nonEmpty, "No string vertex attributes in this segmentation") &&
          FEStatus.assert(
            parent.vertexAttributeNames[String].nonEmpty, "No string vertex attributes in base project")
    def apply(params: Map[String, String]) = {
      val baseIdAttr = parent.vertexAttributes(params("base-id-attr")).runtimeSafeCast[String]
      val segIdAttr = project.vertexAttributes(params("seg-id-attr")).runtimeSafeCast[String]
      val op = graph_operations.ImportEdgeListForExistingVertexSet(
        source(params), params("base-id-field"), params("seg-id-field"))
      seg.belongsTo = op(op.srcVidAttr, baseIdAttr)(op.dstVidAttr, segIdAttr).result.edges
    }
  }
  register("Load segmentation links from CSV",
    new LoadSegmentationLinksOperation(_, _) with CSVRowReader)
  register("Load segmentation links from a database",
    new LoadSegmentationLinksOperation(_, _) with SQLRowReader)

  abstract class ImportSegmentationOperation(t: String, c: Context)
      extends ImportOperation(t, c) with RowReader {
    def parameters = sourceParameters ++ List(
      Param("name", s"Name of new segmentation"),
      Choice("attr", "Vertex ID attribute",
        options = UIValue("!unset", "") +: vertexAttributes[String]),
      Param("base-id-field", "Vertex ID field"),
      Param("seg-id-field", "Segment ID field"))
    def enabled = FEStatus.assert(vertexAttributes[String].nonEmpty, "No string vertex attributes")
    def apply(params: Map[String, String]) = {
      val baseAttrName = params("attr")
      assert(baseAttrName != "!unset", "The Vertex ID attribute parameter must be set.")
      val baseAttr = project.vertexAttributes(baseAttrName).runtimeSafeCast[String]
      val baseId = params("base-id-field")
      assert(baseId.nonEmpty, "The Vertex ID field parameter must be set.")
      val segId = params("seg-id-field")
      assert(segId.nonEmpty, "The Segment ID field parameter must be set.")

      // Import belongs-to relationship as vertices.
      val vertexImport = graph_operations.ImportVertexList(source(params))().result
      // Merge by segment ID to create the segments.
      val merge = {
        val op = graph_operations.MergeVertices[String]()
        op(op.attr, vertexImport.attrs(segId)).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(merge.segments, idAttr = "id")
      // Move segment ID to the segments.
      val segAttr = aggregateViaConnection(
        merge.belongsTo,
        AttributeWithLocalAggregator(vertexImport.attrs(segId), "most_common"))
        .runtimeSafeCast[String]
      segmentation.newVertexAttribute(segId, segAttr)
      // Import belongs-to relationship as edges between the base and the segmentation.
      val edgeImport = {
        val op = graph_operations.ImportEdgeListForExistingVertexSet(source(params), baseId, segId)
        op(op.srcVidAttr, baseAttr)(op.dstVidAttr, segAttr).result
      }
      segmentation.belongsTo = edgeImport.edges
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
    }
  }
  register("Import segmentation from CSV",
    new ImportSegmentationOperation(_, _) with CSVRowReader)
  register("Import segmentation from a database",
    new ImportSegmentationOperation(_, _) with SQLRowReader)

  register("Define segmentation links from matching attributes",
    new StructureOperation(_, _) with SegOp {
      def segmentationParameters = List(
        Choice(
          "base-id-attr",
          s"Identifying vertex attribute in base project",
          options = UIValue.list(parent.vertexAttributeNames[String].toList)),
        Choice(
          "seg-id-attr",
          s"Identifying vertex attribute in segmentation",
          options = vertexAttributes[String]))
      def enabled =
        isSegmentation &&
          FEStatus.assert(
            vertexAttributes[String].nonEmpty, "No string vertex attributes in this segmentation") &&
            FEStatus.assert(
              parent.vertexAttributeNames[String].nonEmpty, "No string vertex attributes in base project")
      def apply(params: Map[String, String]) = {
        val baseIdAttr = parent.vertexAttributes(params("base-id-attr")).runtimeSafeCast[String]
        val segIdAttr = project.vertexAttributes(params("seg-id-attr")).runtimeSafeCast[String]
        val op = graph_operations.EdgesFromBipartiteAttributeMatches[String]()
        seg.belongsTo = op(op.fromAttr, baseIdAttr)(op.toAttr, segIdAttr).result.edges
      }
    })

  register("Union with another project", new StructureOperation(_, _) {
    def parameters = List(
      Choice("other", "Other project's name", options = readableProjects),
      Param("id-attr", "ID attribute name", defaultValue = "new_id"))
    def enabled = hasVertexSet
    override def summary(params: Map[String, String]) = {
      val other = params("other")
      s"Union with $other"
    }

    def checkTypeCollision(other: ProjectViewer) = {
      val commonAttributeNames =
        project.vertexAttributes.keySet & other.vertexAttributes.keySet

      for (name <- commonAttributeNames) {
        val a1 = project.vertexAttributes(name)
        val a2 = other.vertexAttributes(name)
        assert(a1.typeTag.tpe =:= a2.typeTag.tpe,
          s"Attribute '$name' has conflicting types in the two projects: " +
            s"(${a1.typeTag.tpe} and ${a2.typeTag.tpe})")
      }

    }
    def apply(params: Map[String, String]): Unit = {
      val otherName = params("other")
      assert(readableProjects.map(_.id).contains(otherName), s"Unknown project: $otherName")
      val other = ProjectFrame.fromName(otherName).viewer
      if (other.vertexSet == null) {
        // Nothing to do
        return
      }
      checkTypeCollision(other)
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
          (ebInduced.get.induced, ebInduced.get.embedding, null)
        } else if (!ebInduced.isDefined && otherEbInduced.isDefined) {
          (otherEbInduced.get.induced, null, otherEbInduced.get.embedding)
        } else if (ebInduced.isDefined && otherEbInduced.isDefined) {
          val idUnion = {
            val op = graph_operations.VertexSetUnion(2)
            op(
              op.vss,
              Seq(ebInduced.get.induced.idSet, otherEbInduced.get.induced.idSet))
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
        } else {
          (null, null, null)
        }
      val newEdgeAttributes = unifyAttributes(
        project.edgeAttributes
          .map {
            case (name, attr) =>
              name -> graph_operations.PulledOverVertexAttribute.pullAttributeVia(
                attr,
                myEbInjection)
          },
        other.edgeAttributes
          .map {
            case (name, attr) =>
              name -> graph_operations.PulledOverVertexAttribute.pullAttributeVia(
                attr,
                otherEbInjection)
          })

      project.vertexSet = vsUnion.union
      for ((name, attr) <- newVertexAttributes) {
        project.newVertexAttribute(name, attr) // Clear notes.
      }
      val idAttr = params("id-attr")
      assert(
        !project.vertexAttributes.contains(idAttr),
        s"The project already contains a field called '$idAttr'. Please pick a different name.")
      project.newVertexAttribute(idAttr, idAsAttribute(project.vertexSet))
      project.edgeBundle = newEdgeBundle
      project.edgeAttributes = newEdgeAttributes
    }
  })

  register("Fingerprinting based on attributes", new SpecialtyOperation(_, _) {
    def parameters = List(
      Choice("leftName", "First ID attribute", options = vertexAttributes[String]),
      Choice("rightName", "Second ID attribute", options = vertexAttributes[String]),
      Choice("weights", "Edge weights",
        options = UIValue("!no weight", "no weight") +: edgeAttributes[Double]),
      NonNegInt("mo", "Minimum overlap", default = 1),
      Ratio("ms", "Minimum similarity", defaultValue = "0.5"))
    def enabled =
      hasEdgeBundle &&
        FEStatus.assert(vertexAttributes[String].size >= 2, "Two string attributes are needed.")
    def apply(params: Map[String, String]): Unit = {
      val mo = params("mo").toInt
      val ms = params("ms").toDouble
      assert(mo >= 1, "Minimum overlap cannot be less than 1.")
      val leftName = project.vertexAttributes(params("leftName")).runtimeSafeCast[String]
      val rightName = project.vertexAttributes(params("rightName")).runtimeSafeCast[String]
      val weights =
        if (params("weights") == "!no weight") const(project.edgeBundle)
        else project.edgeAttributes(params("weights")).runtimeSafeCast[Double]

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
      project.newVertexAttribute(
        params("leftName") + " similarity score", fingerprinting.leftSimilarities)
      project.newVertexAttribute(
        params("rightName") + " similarity score", fingerprinting.rightSimilarities)
    }
  })

  register("Copy vertex attributes from segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Attribute name prefix", defaultValue = seg.segmentationName))
    def enabled =
      isSegmentation &&
        FEStatus.assert(vertexAttributes.size > 0, "No vertex attributes") &&
        FEStatus.assert(parent.vertexSet != null, s"No vertices on $parent") &&
        FEStatus.assert(seg.belongsTo.properties.isFunction,
          s"Vertices in base project are not guaranteed to be contained in only one segment")
    def apply(params: Map[String, String]): Unit = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- project.vertexAttributes.toMap) {
        parent.newVertexAttribute(
          prefix + name,
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(
            attr, seg.belongsTo))
      }
    }
  })

  register("Copy vertex attributes to segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Attribute name prefix"))
    def enabled =
      isSegmentation &&
        hasVertexSet &&
        FEStatus.assert(parent.vertexAttributes.size > 0, "No vertex attributes on $parent") &&
        FEStatus.assert(seg.belongsTo.properties.isReversedFunction,
          "Segments are not guaranteed to contain only one vertex")
    def apply(params: Map[String, String]): Unit = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- parent.vertexAttributes.toMap) {
        project.newVertexAttribute(
          prefix + name,
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(
            attr, reverse(seg.belongsTo)))
      }
    }
  })

  register("Fingerprinting between project and segmentation", new SpecialtyOperation(_, _) with SegOp {
    def segmentationParameters = List(
      NonNegInt("mo", "Minimum overlap", default = 1),
      Ratio("ms", "Minimum similarity", defaultValue = "0.0"))
    def enabled =
      isSegmentation &&
        hasEdgeBundle && FEStatus.assert(parent.edgeBundle != null, s"No edges on $parent")
    def apply(params: Map[String, String]): Unit = {
      val mo = params("mo").toInt
      val ms = params("ms").toDouble

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
      parent.newVertexAttribute(
        "fingerprinting_similarity_score", fingerprinting.leftSimilarities)
      project.newVertexAttribute(
        "fingerprinting_similarity_score", fingerprinting.rightSimilarities)
    }
  })

  register("Change project notes", new UtilityOperation(_, _) {
    def parameters = List(
      Param("notes", "New contents"))
    def enabled = FEStatus.enabled
    def apply(params: Map[String, String]) = {
      project.notes = params("notes")
    }
  })

  register("Viral modeling", new SpecialtyOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "viral"),
      Choice("target", "Target attribute",
        options = UIValue.list(parentDoubleAttributes)),
      Ratio("test_set_ratio", "Test set ratio", defaultValue = "0.1"),
      RandomSeed("seed", "Random seed for test set selection"),
      NonNegDouble("max_deviation", "Maximal segment deviation", defaultValue = "1.0"),
      NonNegInt("min_num_defined", "Minimum number of defined attributes in a segment", default = 3),
      Ratio("min_ratio_defined", "Minimal ratio of defined attributes in a segment", defaultValue = "0.25"),
      NonNegInt("iterations", "Iterations", default = 3))
    def parentDoubleAttributes = parent.vertexAttributeNames[Double].toList
    def enabled =
      isSegmentation &&
        hasVertexSet &&
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
      parent.newVertexAttribute(s"${prefix}_roles", roles)
      parent.newVertexAttribute(s"${prefix}_${targetName}_test", parted.test)
      var train = parted.train.entity
      val segSizes = computeSegmentSizes(seg)
      project.newVertexAttribute("size", segSizes)
      val maxDeviation = params("max_deviation")

      val coverage = {
        val op = graph_operations.CountAttributes[Double]()
        op(op.attribute, train).result.count
      }
      parent.newVertexAttribute(s"${prefix}_${targetName}_train", train)
      parent.scalars(s"$prefix $targetName coverage initial") = coverage

      var timeOfDefinition = {
        val op = graph_operations.DeriveJSDouble(JavaScript("0"), Seq("attr"))
        op(op.attrs, graph_operations.VertexAttributeToJSValue.seq(train)).result.attr.entity
      }

      // iterative prediction
      for (i <- 1 to params("iterations").toInt) {
        val segTargetAvg = {
          aggregateViaConnection(
            seg.belongsTo,
            AttributeWithLocalAggregator(train, "average"))
            .runtimeSafeCast[Double]
        }
        val segStdDev = {
          aggregateViaConnection(
            seg.belongsTo,
            AttributeWithLocalAggregator(train, "std_deviation"))
            .runtimeSafeCast[Double]
        }
        val segTargetCount = {
          aggregateViaConnection(
            seg.belongsTo,
            AttributeWithLocalAggregator(train, "count"))
            .runtimeSafeCast[Double]
        }
        val segStdDevDefined = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript(s"""
                deviation <= $maxDeviation &&
                defined / ids >= ${params("min_ratio_defined")} &&
                defined >= ${params("min_num_defined")}
                ? deviation
                : undefined"""),
            Seq("deviation", "ids", "defined"))
          op(
            op.attrs,
            graph_operations.VertexAttributeToJSValue.seq(segStdDev, segSizes, segTargetCount))
            .result.attr
        }
        project.newVertexAttribute(
          s"${prefix}_${targetName}_standard_deviation_after_iteration_$i",
          segStdDev)
        project.newVertexAttribute(
          s"${prefix}_${targetName}_average_after_iteration_$i",
          segTargetAvg)
        val predicted = {
          aggregateViaConnection(
            reverse(seg.belongsTo),
            AttributeWithWeightedAggregator(segStdDevDefined, segTargetAvg, "by_min_weight"))
            .runtimeSafeCast[Double]
        }
        train = unifyAttributeT(train, predicted)
        val partedTrain = {
          val op = graph_operations.PartitionAttribute[Double]()
          op(op.attr, train)(op.role, roles).result
        }
        val error = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript("Math.abs(test - train)"), Seq("test", "train"))
          val mae = op(
            op.attrs,
            graph_operations.VertexAttributeToJSValue.seq(
              parted.test.entity, partedTrain.test.entity)).result.attr
          aggregate(AttributeWithAggregator(mae, "average"))
        }
        val coverage = {
          val op = graph_operations.CountAttributes[Double]()
          op(op.attribute, partedTrain.train).result.count
        }
        // the attribute we use for iteration can be defined on the test set as well
        parent.newVertexAttribute(s"${prefix}_${targetName}_after_iteration_$i", train)
        parent.scalars(s"$prefix $targetName coverage after iteration $i") = coverage
        parent.scalars(s"$prefix $targetName mean absolute prediction error after iteration $i") =
          error

        timeOfDefinition = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript(i.toString), Seq("attr"))
          val newDefinitions = op(
            op.attrs, graph_operations.VertexAttributeToJSValue.seq(train)).result.attr
          unifyAttributeT(timeOfDefinition, newDefinitions)
        }
      }
      parent.newVertexAttribute(s"${prefix}_${targetName}_spread_over_iterations", timeOfDefinition)
      // TODO: in the end we should calculate with the fact that the real error where the
      // original attribute is defined is 0.0
    }
  })

  register("Correlate two attributes", new GlobalOperation(_, _) {
    def parameters = List(
      Choice("attrA", "First attribute", options = vertexAttributes[Double]),
      Choice("attrB", "Second attribute", options = vertexAttributes[Double]))
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val attrA = project.vertexAttributes(params("attrA")).runtimeSafeCast[Double]
      val attrB = project.vertexAttributes(params("attrB")).runtimeSafeCast[Double]
      val op = graph_operations.CorrelateAttributes()
      val res = op(op.attrA, attrA)(op.attrB, attrB).result
      project.scalars(s"correlation of ${params("attrA")} and ${params("attrB")}") =
        res.correlation
    }
  })

  register("Filter by attributes", new StructureOperation(_, _) {
    def parameters =
      vertexAttributes.toList.map {
        attr => Param(s"filterva-${attr.id}", attr.id, mandatory = false)
      } ++
        project.segmentations.toList.map {
          seg =>
            Param(
              s"filterva-${seg.viewer.equivalentUIAttribute.title}",
              seg.segmentationName,
              mandatory = false)
        } ++
        edgeAttributes.toList.map {
          attr => Param(s"filterea-${attr.id}", attr.id, mandatory = false)
        }
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes") ||
        FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    val vaFilter = "filterva-(.*)".r
    val eaFilter = "filterea-(.*)".r

    override def summary(params: Map[String, String]) = {
      val filterStrings = params.collect {
        case (vaFilter(name), filter) if filter.nonEmpty => s"$name $filter"
        case (eaFilter(name), filter) if filter.nonEmpty => s"$name $filter"
      }
      "Filter " + filterStrings.mkString(", ")
    }
    def apply(params: Map[String, String]) = {
      val vertexFilters = params.collect {
        case (vaFilter(name), filter) if filter.nonEmpty =>
          // The filter may be for a segmentation's equivalent attribute or for a vertex attribute.
          val segAttrs = project.segmentations.map(_.viewer.equivalentUIAttribute)
          val segGUIDOpt = segAttrs.find(_.title == name).map(_.id)
          val gUID = segGUIDOpt.getOrElse(project.vertexAttributes(name).gUID)
          FEVertexAttributeFilter(gUID.toString, filter)
      }.toSeq

      if (vertexFilters.nonEmpty) {
        val vertexEmbedding = FEFilters.embedFilteredVertices(
          project.vertexSet, vertexFilters, heavy = true)
        project.pullBack(vertexEmbedding)
      }
      val edgeFilters = params.collect {
        case (eaFilter(name), filter) if filter.nonEmpty =>
          val attr = project.edgeAttributes(name)
          FEVertexAttributeFilter(attr.gUID.toString, filter)
      }.toSeq
      assert(vertexFilters.nonEmpty || edgeFilters.nonEmpty, "No filters specified.")
      if (edgeFilters.nonEmpty) {
        val edgeEmbedding = FEFilters.embedFilteredVertices(
          project.edgeBundle.idSet, edgeFilters, heavy = true)
        project.pullBackEdges(edgeEmbedding)
      }
    }
  })

  register("Save UI status as graph attribute", new UtilityOperation(_, _) {
    def parameters = List(
      // In the future we may want a special kind for this so that users don't see JSON.
      Param("scalarName", "Name of new graph attribute"),
      Param("uiStatusJson", "UI status as JSON"))

    def enabled = FEStatus.enabled

    def apply(params: Map[String, String]) = {
      import UIStatusSerialization._
      val j = json.Json.parse(params("uiStatusJson"))
      val uiStatus = j.as[UIStatus]
      project.scalars(params("scalarName")) =
        graph_operations.CreateUIStatusScalar(uiStatus).result.created
    }
  })

  register("Metagraph", new StructureOperation(_, _) {
    def parameters = List(
      Param("timestamp", "Current timestamp", defaultValue = graph_util.Timestamp.toString))
    def enabled =
      FEStatus.assert(user.isAdmin, "Requires administrator privileges") && hasNoVertexSet
    private def shortClass(o: Any) = o.getClass.getName.split('.').last
    def apply(params: Map[String, String]) = {
      val t = params("timestamp")
      val directory = HadoopFile("UPLOAD$") / s"metagraph-$t"
      val ops = env.metaGraphManager.getOperationInstances
      val vertices = {
        val file = directory / "vertices"
        if (!file.exists) {
          val lines = ops.flatMap {
            case (guid, inst) =>
              val op = s"$guid,Operation,${shortClass(inst.operation)},"
              val outputs = inst.outputs.all.map {
                case (name, entity) =>
                  val calc = env.dataManager.isCalculated(entity)
                  s"${entity.gUID},${shortClass(entity)},${name.name},$calc"
              }
              op +: outputs.toSeq
          }
          log.info(s"Writing metagraph vertices to $file.")
          file.createFromStrings(lines.mkString("\n"))
        }
        val csv = graph_operations.CSV(
          file,
          delimiter = ",",
          header = "guid,kind,name,is_calculated")
        graph_operations.ImportVertexList(csv)().result
      }
      project.vertexSet = vertices.vertices
      for ((name, attr) <- vertices.attrs) {
        project.newVertexAttribute(name, attr)
      }
      project.newVertexAttribute("id", idAsAttribute(project.vertexSet))
      val guids = project.vertexAttributes("guid").runtimeSafeCast[String]
      val edges = {
        val file = directory / "edges"
        if (!file.exists) {
          val lines = ops.flatMap {
            case (guid, inst) =>
              val inputs = inst.inputs.all.map {
                case (name, entity) => s"${entity.gUID},$guid,Input,${name.name}"
              }
              val outputs = inst.outputs.all.map {
                case (name, entity) => s"$guid,${entity.gUID},Output,${name.name}"
              }
              inputs ++ outputs
          }
          log.info(s"Writing metagraph edges to $file.")
          file.createFromStrings(lines.mkString("\n"))
        }
        val csv = graph_operations.CSV(
          file,
          delimiter = ",",
          header = "src,dst,kind,name")
        val op = graph_operations.ImportEdgeListForExistingVertexSet(csv, "src", "dst")
        op(op.srcVidAttr, guids)(op.dstVidAttr, guids).result
      }
      project.edgeBundle = edges.edges
      project.edgeAttributes = edges.attrs.mapValues(_.entity)
    }
  })

  register("Copy edges to segmentation", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List()
    def enabled = isSegmentation && hasNoEdgeBundle &&
      FEStatus.assert(parent.edgeBundle != null, "No edges on base project")
    def apply(params: Map[String, String]) = {
      val induction = {
        val op = graph_operations.InducedEdgeBundle()
        op(op.srcMapping, seg.belongsTo)(op.dstMapping, seg.belongsTo)(op.edges, parent.edgeBundle).result
      }
      project.edgeBundle = induction.induced
      for ((name, attr) <- parent.edgeAttributes) {
        project.edgeAttributes(name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(attr, induction.embedding)
      }
    }
  })

  { // "Dirty operations", that is operations that use a data manager. Think twice if you really
    // need this before putting an operation here.
    implicit lazy val dataManager = env.dataManager

    register("Export vertex attributes to file", new ExportOperation(_, _) {
      override val dirty = true
      def parameters = List(
        Param("path", "Destination path", defaultValue = "<auto>"),
        Param("link", "Download link name", defaultValue = "vertex_attributes_csv"),
        Choice("attrs", "Attributes", options = vertexAttributes, multipleChoice = true),
        Choice("format", "File format", options = UIValue.list(List("CSV"))))
      def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes.")
      def apply(params: Map[String, String]) = {
        assert(params("attrs").nonEmpty, "No attributes are selected for export.")
        val labels = params("attrs").split(",", -1)
        val attrs: Map[String, Attribute[_]] = labels.map {
          label => label -> project.vertexAttributes(label)
        }.toMap
        val path = getExportFilename(params("path"))
        params("format") match {
          case "CSV" =>
            val csv = graph_util.CSVExport.exportVertexAttributes(project.vertexSet, attrs)
            csv.saveToDir(path)
        }
        project.scalars(params("link")) =
          downloadLink(path, "export_" + params("link"))
      }
    })

    register("Export vertex attributes to database", new ExportOperation(_, _) {
      override val dirty = true
      def parameters = List(
        Param("db", "Database"),
        Param("table", "Table"),
        Choice("attrs", "Attributes", options = vertexAttributes, multipleChoice = true),
        Choice("delete", "Overwrite table if it exists", options = UIValue.list(List("no", "yes"))))
      def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes.")
      def apply(params: Map[String, String]) = {
        assert(params("attrs").nonEmpty, "No attributes are selected for export.")
        val labels = params("attrs").split(",", -1)
        val attrs: Seq[(String, Attribute[_])] = labels.map {
          label => label -> project.vertexAttributes(label)
        }
        val export = graph_util.SQLExport(params("table"), project.vertexSet, attrs.toMap)
        export.insertInto(
          params("db"),
          if (params("delete") == "yes") "overwrite" else "error")
      }
    })

    def getExportFilename(param: String): HadoopFile = {
      assert(param.nonEmpty, "No export path specified.")
      if (param == "<auto>") {
        dataManager.repositoryPath / "exports" / graph_util.Timestamp.toString
      } else {
        HadoopFile(param)
      }
    }

    case class NameAndAttr[T](name: String, attr: Attribute[T]) {
      def asPair: (String, Attribute[_]) = {
        (name, attr)
      }
    }

    def getNameAndVertexAttr(
      paramID: String,
      pe: ProjectEditor): NameAndAttr[_] = {

      if (paramID == "!internal id (default)") {
        NameAndAttr("id", idAsAttribute(pe.vertexSet))
      } else {
        NameAndAttr(paramID, pe.vertexAttributes(paramID))
      }
    }

    def getPrefixedNameAndEdgeAttribute(
      nameAndVertexAttr: NameAndAttr[_],
      targetEb: EdgeBundle,
      isSrc: Boolean): NameAndAttr[_] = {

      if (isSrc) {
        NameAndAttr(
          "src_" + nameAndVertexAttr.name,
          graph_operations.VertexToEdgeAttribute.srcAttribute(nameAndVertexAttr.attr, targetEb)
        )
      } else {
        NameAndAttr(
          "dst_" + nameAndVertexAttr.name,
          graph_operations.VertexToEdgeAttribute.dstAttribute(nameAndVertexAttr.attr, targetEb)
        )
      }
    }

    register("Export edge attributes to file", new ExportOperation(_, _) {
      override val dirty = true
      def parameters = List(
        Param("path", "Destination path", defaultValue = "<auto>"),
        Param("link", "Download link name", defaultValue = "edge_attributes_csv"),
        Choice("attrs", "Attributes", options = edgeAttributes, multipleChoice = true),
        Choice("id_attr", "Vertex id attribute",
          options = UIValue("!internal id (default)", "internal id (default)") +: vertexAttributes),
        Choice("format", "File format", options = UIValue.list(List("CSV"))))
      def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes.")
      def apply(params: Map[String, String]) = {
        assert(params("attrs").nonEmpty, "No attributes are selected for export.")
        val labels = params("attrs").split(",", -1)
        val attrs: Seq[(String, Attribute[_])] = labels.map {
          label => label -> project.edgeAttributes(label)
        }.sortBy(_._1)

        val path = getExportFilename(params("path"))
        val idNameAndVertexAttr = getNameAndVertexAttr(params("id_attr"), project)
        val srcNameAndEdgeAttr =
          getPrefixedNameAndEdgeAttribute(idNameAndVertexAttr, project.edgeBundle, isSrc = true)
        val dstNameAndEdgeAttr =
          getPrefixedNameAndEdgeAttribute(idNameAndVertexAttr, project.edgeBundle, isSrc = false)

        params("format") match {
          case "CSV" =>
            val csv =
              graph_util.CSVExport.exportEdgeAttributes(project.edgeBundle,
                srcNameAndEdgeAttr.asPair +: dstNameAndEdgeAttr.asPair +: attrs)
            csv.saveToDir(path)
        }
        project.scalars(params("link")) =
          downloadLink(path, "export_" + params("link"))
      }
    })

    register("Export edge attributes to database", new ExportOperation(_, _) {
      override val dirty = true
      def parameters = List(
        Param("db", "Database"),
        Param("table", "Table"),
        Choice("attrs", "Attributes", options = edgeAttributes, multipleChoice = true),
        Choice("delete", "Overwrite table if it exists", options = UIValue.list(List("no", "yes"))))
      def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes.")
      def apply(params: Map[String, String]) = {
        assert(params("attrs").nonEmpty, "No attributes are selected for export.")
        val labels = params("attrs").split(",", -1)
        val attrs: Map[String, Attribute[_]] = labels.map {
          label => label -> project.edgeAttributes(label)
        }.toMap
        val export = graph_util.SQLExport(params("table"), project.edgeBundle, attrs)
        export.insertInto(
          params("db"),
          if (params("delete") == "yes") "overwrite" else "error")
      }
    })

    register("Export segmentation to file", new ExportOperation(_, _) with SegOp {
      override val dirty = true
      def segmentationParameters = List(
        Param("path", "Destination path", defaultValue = "<auto>"),
        Param("link", "Download link name", defaultValue = "segmentation_csv"),
        Choice("base_id_attr", "Project vertex id attribute",
          options = UIValue("!internal id (default)", "internal id (default)") +:
            UIValue.list(parent.vertexAttributeNames.toList)),
        Choice("seg_id_attr", "Segmentation vertex id attribute",
          options = UIValue("!internal id (default)", "internal id (default)") +: vertexAttributes),
        Choice("format", "File format", options = UIValue.list(List("CSV"))))
      def enabled = isSegmentation && FEStatus.enabled
      def apply(params: Map[String, String]) = {
        val path = getExportFilename(params("path"))
        val name = project.asSegmentation.segmentationName

        val srcNameAndVertexAttr = getNameAndVertexAttr(params("base_id_attr"), parent)
        val dstNameAndVertexAttr = getNameAndVertexAttr(params("seg_id_attr"), project)
        val srcNameAndEdgeAttr =
          getPrefixedNameAndEdgeAttribute(srcNameAndVertexAttr, seg.belongsTo, isSrc = true)
        val dstNameAndEdgeAttr =
          getPrefixedNameAndEdgeAttribute(dstNameAndVertexAttr, seg.belongsTo, isSrc = false)

        params("format") match {
          case "CSV" =>
            val csv = graph_util.CSVExport.exportEdgeAttributes(
              seg.belongsTo, Seq(srcNameAndEdgeAttr.asPair, dstNameAndEdgeAttr.asPair))
            csv.saveToDir(path)
        }
        project.scalars(params("link")) =
          downloadLink(path, "export_" + params("link"))
      }
    })

    register("Export segmentation to database", new ExportOperation(_, _) with SegOp {
      override val dirty = true
      def segmentationParameters = List(
        Param("db", "Database"),
        Param("table", "Table"),
        Choice("delete", "Overwrite table if it exists", options = UIValue.list(List("no", "yes"))))
      def enabled = isSegmentation && FEStatus.enabled
      def apply(params: Map[String, String]) = {
        val export = graph_util.SQLExport(params("table"), seg.belongsTo, Map[String, Attribute[_]]())
        export.insertInto(
          params("db"),
          if (params("delete") == "yes") "overwrite" else "error")
      }
    })
  }

  def joinAttr[A, B](a: Attribute[A], b: Attribute[B]): Attribute[(A, B)] = {
    graph_operations.JoinAttributes.run(a, b)
  }

  def computeSegmentSizes(segmentation: SegmentationEditor): Attribute[Double] = {
    val op = graph_operations.OutDegree()
    op(op.es, reverse(segmentation.belongsTo)).result.outDegree
  }

  def toDouble(attr: Attribute[_]): Attribute[Double] = {
    if (attr.is[String])
      graph_operations.VertexAttributeToDouble.run(attr.runtimeSafeCast[String])
    else if (attr.is[Long])
      graph_operations.LongAttributeToDouble.run(attr.runtimeSafeCast[Long])
    else
      throw new AssertionError(s"Unexpected type (${attr.typeTag}) on $attr")
  }

  private def attributeToString[T](attr: Attribute[T]): Attribute[String] = {
    val op = graph_operations.VertexAttributeToString[T]()
    op(op.attr, attr).result.attr
  }

  def parseAggregateParams(params: Map[String, String]) = {
    val aggregate = "aggregate-(.*)".r
    params.toSeq.collect {
      case (aggregate(attr), choices) if choices.nonEmpty => attr -> choices
    }.flatMap {
      case (attr, choices) => choices.split(",", -1).map(attr -> _)
    }
  }
  def aggregateParams(
    attrs: Iterable[(String, Attribute[_])],
    needsGlobal: Boolean = false,
    weighted: Boolean = false): List[OperationParameterMeta] = {
    attrs.toList.map {
      case (name, attr) =>
        val options = if (attr.is[Double]) {
          if (weighted) { // At the moment all weighted aggregators are global.
            UIValue.list(List("weighted_sum", "weighted_average", "by_max_weight", "by_min_weight"))
          } else if (needsGlobal) {
            UIValue.list(List("sum", "average", "min", "max", "count", "first", "std_deviation"))
          } else {
            UIValue.list(List(
              "sum", "average", "min", "max", "most_common", "count_distinct",
              "count", "vector", "set", "std_deviation"))
          }
        } else if (attr.is[String]) {
          if (weighted) { // At the moment all weighted aggregators are global.
            UIValue.list(List("by_max_weight", "by_min_weight"))
          } else if (needsGlobal) {
            UIValue.list(List("count", "first"))
          } else {
            UIValue.list(List(
              "most_common", "count_distinct", "majority_50", "majority_100",
              "count", "vector", "set"))
          }
        } else {
          if (weighted) { // At the moment all weighted aggregators are global.
            UIValue.list(List("by_max_weight", "by_min_weight"))
          } else if (needsGlobal) {
            UIValue.list(List("count", "first"))
          } else {
            UIValue.list(List("most_common", "count_distinct", "count", "vector", "set"))
          }
        }
        TagList(s"aggregate-$name", name, options = options)
    }
  }

  // Performs AggregateAttributeToScalar.
  private def aggregate[From, Intermediate, To](
    attributeWithAggregator: AttributeWithAggregator[From, Intermediate, To]): Scalar[To] = {
    val op = graph_operations.AggregateAttributeToScalar(attributeWithAggregator.aggregator)
    op(op.attr, attributeWithAggregator.attr).result.aggregated
  }

  // Performs AggregateByEdgeBundle.
  private def aggregateViaConnection[From, To](
    connection: EdgeBundle,
    attributeWithAggregator: AttributeWithLocalAggregator[From, To]): Attribute[To] = {
    val op = graph_operations.AggregateByEdgeBundle(attributeWithAggregator.aggregator)
    op(op.connection, connection)(op.attr, attributeWithAggregator.attr).result.attr
  }

  // Performs AggregateFromEdges.
  private def aggregateFromEdges[From, To](
    edges: EdgeBundle,
    attributeWithAggregator: AttributeWithLocalAggregator[From, To]): Attribute[To] = {
    val op = graph_operations.AggregateFromEdges(attributeWithAggregator.aggregator)
    val res = op(op.edges, edges)(op.eattr, attributeWithAggregator.attr).result
    res.dstAttr
  }

  def reverse(eb: EdgeBundle): EdgeBundle = {
    val op = graph_operations.ReverseEdges()
    op(op.esAB, eb).result.esBA
  }

  def makeEdgeBundleSymmetric(eb: EdgeBundle): EdgeBundle = {
    val op = graph_operations.MakeEdgeBundleSymmetric()
    op(op.es, eb).result.symmetric
  }

  def addReversed(eb: EdgeBundle): EdgeBundle = {
    val op = graph_operations.AddReversedEdges()
    op(op.es, eb).result.esPlus
  }

  def stripDuplicateEdges(eb: EdgeBundle): EdgeBundle = {
    val op = graph_operations.StripDuplicateEdgesFromBundle()
    op(op.es, eb).result.unique
  }

  object Direction {
    // Options suitable when edge attributes are involved.
    val attrOptions = UIValue.list(List(
      "incoming edges",
      "outgoing edges",
      "all edges"))
    // Options suitable when edge attributes are not involved.
    val options = attrOptions :+
      UIValue("symmetric edges", "symmetric edges") :+
      UIValue("in-neighbors", "in-neighbors") :+
      UIValue("out-neighbors", "out-neighbors") :+
      UIValue("all neighbors", "all neighbors") :+
      UIValue("symmetric neighbors", "symmetric neighbors")
    // Neighborhood directions correspond to these
    // edge directions, but they also retain only one A->B edge in
    // the output edgeBundle
    private val neighborOptionMapping = Map(
      "in-neighbors" -> "incoming edges",
      "out-neighbors" -> "outgoing edges",
      "all neighbors" -> "all edges",
      "symmetric neighbors" -> "symmetric edges"
    )
  }
  case class Direction(direction: String, origEB: EdgeBundle, reversed: Boolean = false) {
    val unchangedOut: (EdgeBundle, Option[EdgeBundle]) = (origEB, None)
    val reversedOut: (EdgeBundle, Option[EdgeBundle]) = {
      val op = graph_operations.ReverseEdges()
      val res = op(op.esAB, origEB).result
      (res.esBA, Some(res.injection))
    }
    private def computeEdgeBundleAndPullBundleOpt(dir: String): (EdgeBundle, Option[EdgeBundle]) = {
      dir match {
        case "incoming edges" => if (reversed) reversedOut else unchangedOut
        case "outgoing edges" => if (reversed) unchangedOut else reversedOut
        case "all edges" =>
          val op = graph_operations.AddReversedEdges()
          val res = op(op.es, origEB).result
          (res.esPlus, Some(res.newToOriginal))
        case "symmetric edges" =>
          // Use "null" as the injection because it is an error to use
          // "symmetric edges" with edge attributes.
          (makeEdgeBundleSymmetric(origEB), Some(null))
      }
    }

    val (edgeBundle, pullBundleOpt): (EdgeBundle, Option[EdgeBundle]) = {
      if (Direction.neighborOptionMapping.contains(direction)) {
        val (eB, pBO) = computeEdgeBundleAndPullBundleOpt(Direction.neighborOptionMapping(direction))
        (stripDuplicateEdges(eB), pBO)
      } else {
        computeEdgeBundleAndPullBundleOpt(direction)
      }
    }

    def pull[T](attribute: Attribute[T]): Attribute[T] = {
      pullBundleOpt.map { pullBundle =>
        graph_operations.PulledOverVertexAttribute.pullAttributeVia(attribute, pullBundle)
      }.getOrElse(attribute)
    }
  }

  def count(eb: EdgeBundle): Scalar[Long] = graph_operations.Count.run(eb)

  private def unifyAttributeT[T](a1: Attribute[T], a2: Attribute[_]): Attribute[T] = {
    val op = graph_operations.AttributeFallback[T]()
    op(op.originalAttr, a1)(op.defaultAttr, a2.runtimeSafeCast(a1.typeTag)).result.defaultedAttr
  }
  def unifyAttribute(a1: Attribute[_], a2: Attribute[_]): Attribute[_] = {
    unifyAttributeT(a1, a2)
  }

  def unifyAttributes(
    as1: Iterable[(String, Attribute[_])],
    as2: Iterable[(String, Attribute[_])]): Map[String, Attribute[_]] = {

    val m1 = as1.toMap
    val m2 = as2.toMap
    m1.keySet.union(m2.keySet)
      .map(k => k -> (m1.get(k) ++ m2.get(k)).reduce(unifyAttribute _))
      .toMap
  }

  def concat(eb1: EdgeBundle, eb2: EdgeBundle): EdgeBundle = {
    new graph_util.BundleChain(Seq(eb1, eb2)).getCompositeEdgeBundle._1
  }

  def const(eb: EdgeBundle, value: Double = 1.0): Attribute[Double] = {
    graph_operations.AddConstantAttribute.run(eb.idSet, value)
  }

  def newScalar(data: String): Scalar[String] = {
    val op = graph_operations.CreateStringScalar(data)
    op.result.created
  }

  def downloadLink(fn: HadoopFile, name: String) = {
    val urlPath = java.net.URLEncoder.encode(fn.symbolicName, "utf-8")
    val urlName = java.net.URLEncoder.encode(name, "utf-8")
    val url = s"/download?path=$urlPath&name=$urlName"
    val quoted = '"' + url + '"'
    newScalar(s"<a href=$quoted>download</a>")
  }

  // Whether a JavaScript expression contains a given identifier.
  // It's a best-effort implementation with no guarantees of correctness.
  def containsIdentifierJS(expr: String, identifier: String): Boolean = {
    val re = "(?s).*\\b" + java.util.regex.Pattern.quote(identifier) + "\\b.*"
    expr.matches(re)
  }
}

object Operations {
  def addNotesOperation(notes: String): FEOperationSpec = {
    FEOperationSpec("Change-project-notes", Map("notes" -> notes))
  }
}
