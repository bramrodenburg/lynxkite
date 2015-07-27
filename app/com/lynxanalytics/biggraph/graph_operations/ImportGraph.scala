// Operations and other classes for importing data in general and from CSV files.
package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_util.HadoopFile
import com.lynxanalytics.biggraph.protection.Limitations
import com.lynxanalytics.biggraph.spark_util.RDDUtils
import com.lynxanalytics.biggraph.spark_util.SortedRDD
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }

import org.apache.commons.lang.StringEscapeUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.Partitioner

// Functions for looking at CSV files. The frontend can use these when
// constructing the import operation.
object ImportUtil {
  def header(file: HadoopFile): String = {
    // TODO: we don't check here if all the files begin with the same header!
    val files = file.list
    assert(files.nonEmpty, s"Not found: $file")
    // Read from first file if there is a glob.
    files.head.readFirstLine()
  }

  private[graph_operations] def splitter(delimiter: String): String => Seq[String] = {
    val delim = java.util.regex.Pattern.quote(delimiter)
    def oneOf(options: String*) = options.mkString("|")
    def any(p: String) = capture(p) + "*"
    def capture(p: String) = "(" + p + ")"
    def oneField(p: String) = oneOf(capture(p + delim), capture(p + "$")) // Delimiter or line end.
    val quote = "\""
    val nonQuote = "[^\"]"
    val doubleQuote = quote + quote
    val quotedString = quote + any(oneOf(nonQuote, doubleQuote)) + quote
    val anyString = ".*?"
    val r = oneOf(oneField(quotedString), oneField(anyString)).r
    val splitter = { line: String =>
      val matches = r.findAllMatchIn(line)
      // Find the top-level group that has matched in each field.
      val fields = matches.map(_.subgroups.find(_ != null).get).toList
      // The regex will always have an empty match at the end, which we may or may not need to
      // include. We include all the matches that end with a comma, plus one that does not.
      val lastIndex = fields.indexWhere(!_.endsWith(delimiter))
      val fixed = fields.take(lastIndex + 1).map(_.stripSuffix(delimiter))
      // Remove quotes and unescape double-quotes in quoted fields.
      fixed.map { field =>
        if (field.startsWith(quote) && field.endsWith(quote)) {
          field.slice(1, field.length - 1).replace(doubleQuote, quote)
        } else field
      }
    }
    return splitter
  }

  private val splitters = collection.mutable.Map[String, String => Seq[String]]()

  // Splits a line by the delimiter. Delimiters inside quoted fields are ignored. (They become part
  // of the string.) Quotes inside quoted fields must be escaped by doubling them (" -> "").
  // TODO: Maybe we should use a CSV library.
  private[graph_operations] def split(line: String, delimiter: String): Seq[String] = {
    // Cache the regular expressions.
    if (!splitters.contains(delimiter)) {
      splitters(delimiter) = splitter(delimiter)
    }
    return splitters(delimiter)(line)
  }
}

trait RowInput extends ToJson {
  def fields: Seq[String]
  def lines(rc: RuntimeContext): SortedRDD[ID, Seq[String]]
  val mayHaveNulls: Boolean
}

object CSV extends FromJson[CSV] {
  def fromJson(j: JsValue): CSV = {
    val header = (j \ "header").as[String]
    val delimiter = (j \ "delimiter").as[String]
    val omitFields = (j \ "omitFields").asOpt[Set[String]].getOrElse(Set[String]())
    val fields = getFields(delimiter, header)
    new CSV(
      HadoopFile((j \ "file").as[String], true),
      delimiter,
      header,
      fields,
      omitFields,
      JavaScript((j \ "filter").as[String]))
  }

  def getFields(delimiter: String, header: String): Seq[String] = {
    val unescapedDelimiter = StringEscapeUtils.unescapeJava(delimiter)
    ImportUtil.split(header, unescapedDelimiter).map(_.trim)
  }

  def apply(file: HadoopFile,
            delimiter: String,
            header: String,
            omitFields: Set[String] = Set(),
            filter: JavaScript = JavaScript("")): CSV = {
    val fields = getFields(delimiter, header)
    assert(
      fields.forall(_.nonEmpty),
      s"CSV column with empty name is not allowed. Column names were: $fields")
    assert(
      (fields.toSet.size == fields.size),
      s"Duplicate CSV column name is not allowed. Column names were: $fields")
    assert(file.list.nonEmpty, s"$file does not exist.")
    assert(
      omitFields.forall(fields.contains(_)),
      {
        val missingColumns = omitFields.filter(!fields.contains(_)).mkString(", ")
        s"Column(s) $missingColumns that you asked to omit are not actually columns."
      })
    new CSV(file, delimiter, header, fields, omitFields, filter)
  }
}
case class CSV private (file: HadoopFile,
                        delimiter: String,
                        header: String,
                        allFields: Seq[String],
                        omitFields: Set[String],
                        filter: JavaScript) extends RowInput {
  val unescapedDelimiter = StringEscapeUtils.unescapeJava(delimiter)
  val fields = allFields.filter(field => !omitFields.contains(field))
  override def toJson = {
    val withoutOmits = Json.obj(
      "file" -> file.symbolicName,
      "delimiter" -> delimiter,
      "header" -> header,
      "filter" -> filter.expression)
    if (omitFields.isEmpty) {
      withoutOmits
    } else {
      withoutOmits + ("omitFields" -> Json.toJson(omitFields))
    }
  }

  def lines(rc: RuntimeContext): SortedRDD[ID, Seq[String]] = {
    val globLength = file.globLength
    // Estimate row count by the CSV file size. Underestimating results in more partitions.
    val rowLength = System.getProperty("biggraph.csv.row.length", "20").toLong
    val partitioner = rc.partitionerForNRows(globLength / rowLength)
    val lines = file.loadTextFile(rc.sparkContext)
    // Only repartition if we need more partitions.
    val numPartitions = lines.partitions.size max partitioner.numPartitions
    log.info(s"Reading $file ($globLength bytes) into $numPartitions partitions.")
    val fullRows = lines
      .filter(_ != header)
      .map(ImportUtil.split(_, unescapedDelimiter))
      .filter(jsFilter(_))
    val keptFields = if (omitFields.nonEmpty) {
      val keptIndices = allFields.zipWithIndex.filter(x => !omitFields.contains(x._1)).map(_._2)
      fullRows.map(fullRow => keptIndices.map(idx => fullRow(idx)))
    } else {
      fullRows
    }
    keptFields.randomNumbered(numPartitions)
  }

  val mayHaveNulls = false

  def jsFilter(line: Seq[String]): Boolean = {
    if (line.length != allFields.length) {
      log.info(s"Input line cannot be parsed: $line")
      return false
    }
    return filter.isTrue(allFields.zip(line).toMap)
  }
}

trait ImportCommon {
  class Columns(
      allNumberedLines: SortedRDD[ID, Seq[String]],
      fields: Seq[String],
      mayHaveNulls: Boolean,
      requiredFields: Set[String] = Set()) {
    val numberedValidLines =
      if (requiredFields.isEmpty || !mayHaveNulls) allNumberedLines
      else {
        val requiredIndexes = requiredFields.map(fieldName => fields.indexOf(fieldName)).toArray
        allNumberedLines.filter {
          case (id, line) => requiredIndexes.forall(idx => line(idx) != null)
        }
      }
    val singleColumns = fields.zipWithIndex.map {
      case (field, idx) =>
        (field,
          if (mayHaveNulls) numberedValidLines.flatMapValues(line => Option(line(idx)))
          else numberedValidLines.mapValues(line => line(idx)))
    }.toMap

    def apply(fieldName: String) = singleColumns(fieldName)

    def columnPair(fieldName1: String, fieldName2: String): SortedRDD[ID, (String, String)] = {
      val idx1 = fields.indexOf(fieldName1)
      val idx2 = fields.indexOf(fieldName2)
      if (mayHaveNulls) {
        numberedValidLines.flatMapValues { line =>
          val value1 = line(idx1)
          val value2 = line(idx2)
          if ((value1 != null) && (value2 != null)) Some((value1, value2))
          else None
        }
      } else {
        numberedValidLines.mapValues(line => (line(idx1), line(idx2)))
      }
    }
  }

  val input: RowInput

  protected def mustHaveField(field: String) = {
    assert(input.fields.contains(field), s"No such field: $field in ${input.fields}")
  }

  protected def readColumns(
    rc: RuntimeContext,
    input: RowInput,
    requiredFields: Set[String] = Set()): Columns = {
    val numbered = input.lines(rc)
    numbered.cacheBackingArray()
    val maxLines = Limitations.maxImportedLines
    if (maxLines >= 0) {
      val numLines = numbered.count
      if (numLines > maxLines) {
        throw new AssertionError(
          s"Can't import $numLines lines as your licence only allows $maxLines.")
      }
    }
    return new Columns(numbered, input.fields, input.mayHaveNulls, requiredFields)
  }
}
object ImportCommon {
  def toSymbol(field: String) = Symbol("imported_field_" + field)
  def checkIdMapping(rdd: RDD[(String, ID)], partitioner: Partitioner): SortedRDD[String, ID] =
    rdd.groupBySortedKey(partitioner)
      .mapValuesWithKeys {
        case (key, id) =>
          assert(id.size == 1,
            s"The ID attribute must contain unique keys. $key appears ${id.size} times.")
          id.head
      }
}

object ImportVertexList extends OpFromJson {
  class Output(implicit instance: MetaGraphOperationInstance,
               fields: Seq[String]) extends MagicOutput(instance) {
    val vertices = vertexSet
    val attrs = fields.map {
      f => f -> vertexAttribute[String](vertices, ImportCommon.toSymbol(f))
    }.toMap
  }
  def fromJson(j: JsValue) = ImportVertexList(TypedJson.read[RowInput](j \ "input"))
}
case class ImportVertexList(input: RowInput) extends ImportCommon
    with TypedMetaGraphOp[NoInput, ImportVertexList.Output] {
  import ImportVertexList._
  override val isHeavy = true
  @transient override lazy val inputs = new NoInput()
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, input.fields)
  override def toJson = Json.obj("input" -> input.toTypedJson)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    val columns = readColumns(rc, input)
    for ((field, rdd) <- columns.singleColumns) {
      output(o.attrs(field), rdd)
    }
    output(o.vertices, columns.numberedValidLines.mapValues(_ => ()))
  }
}

trait ImportEdges extends ImportCommon {
  val src: String
  val dst: String
  mustHaveField(src)
  mustHaveField(dst)

  def putEdgeAttributes(columns: Columns,
                        oattr: Map[String, EntityContainer[Attribute[String]]],
                        output: OutputBuilder): Unit = {
    for ((field, rdd) <- columns.singleColumns) {
      output(oattr(field), rdd)
    }
  }

  def edgeSrcDst(columns: Columns) = columns.columnPair(src, dst)
}

object ImportEdgeList extends OpFromJson {
  class Output(implicit instance: MetaGraphOperationInstance,
               fields: Seq[String])
      extends MagicOutput(instance) {
    val (vertices, edges) = graph
    val attrs = fields.map {
      f => f -> edgeAttribute[String](edges, ImportCommon.toSymbol(f))
    }.toMap
    val stringID = vertexAttribute[String](vertices)
  }
  def fromJson(j: JsValue) =
    ImportEdgeList(TypedJson.read[RowInput](j \ "input"), (j \ "src").as[String], (j \ "dst").as[String])
}
case class ImportEdgeList(input: RowInput, src: String, dst: String)
    extends ImportEdges
    with TypedMetaGraphOp[NoInput, ImportEdgeList.Output] {
  import ImportEdgeList._
  override val isHeavy = true
  @transient override lazy val inputs = new NoInput()
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, input.fields)
  override def toJson = Json.obj("input" -> input.toTypedJson, "src" -> src, "dst" -> dst)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    val columns = readColumns(rc, input, Set(src, dst))
    val partitioner = columns(src).partitioner.get
    putEdgeAttributes(columns, o.attrs, output)
    val namesWithCounts = columns.columnPair(src, dst).values.flatMap(sd => Iterator(sd._1, sd._2))
      .map(x => x -> 1L)
      .reduceByKey(partitioner, _ + _)
    val idToNameWithCount = namesWithCounts.randomNumbered(partitioner)
    val nameToIdWithCount = idToNameWithCount
      .map { case (id, (name, count)) => (name, (id, count)) }
      .toSortedRDD(partitioner)
    val srcResolvedByDst = RDDUtils.hybridLookupUsingCounts(
      edgeSrcDst(columns).map {
        case (edgeId, (src, dst)) => src -> (edgeId, dst)
      },
      nameToIdWithCount)
      .map { case (src, ((edgeId, dst), sid)) => dst -> (edgeId, sid) }

    val edges = RDDUtils.hybridLookupUsingCounts(srcResolvedByDst, nameToIdWithCount)
      .map { case (dst, ((edgeId, sid), did)) => edgeId -> Edge(sid, did) }
      .toSortedRDD(partitioner)

    output(o.edges, edges)
    output(o.vertices, idToNameWithCount.mapValues(_ => ()))
    output(o.stringID, idToNameWithCount.mapValues(_._1))
  }
}

object ImportEdgeListForExistingVertexSet extends OpFromJson {
  class Input extends MagicInputSignature {
    val sources = vertexSet
    val destinations = vertexSet
    val srcVidAttr = vertexAttribute[String](sources)
    val dstVidAttr = vertexAttribute[String](destinations)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input,
               fields: Seq[String])
      extends MagicOutput(instance) {
    val edges = edgeBundle(inputs.sources.entity, inputs.destinations.entity)
    val attrs = fields.map {
      f => f -> edgeAttribute[String](edges, ImportCommon.toSymbol(f))
    }.toMap
  }
  def fromJson(j: JsValue) =
    ImportEdgeListForExistingVertexSet(TypedJson.read[RowInput](j \ "input"), (j \ "src").as[String], (j \ "dst").as[String])
}
case class ImportEdgeListForExistingVertexSet(input: RowInput, src: String, dst: String)
    extends ImportEdges
    with TypedMetaGraphOp[ImportEdgeListForExistingVertexSet.Input, ImportEdgeListForExistingVertexSet.Output] {
  import ImportEdgeListForExistingVertexSet._
  override val isHeavy = true
  @transient override lazy val inputs = new Input()
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs, input.fields)
  override def toJson = Json.obj("input" -> input.toTypedJson, "src" -> src, "dst" -> dst)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val columns = readColumns(rc, input)
    val partitioner = columns(src).partitioner.get
    putEdgeAttributes(columns, o.attrs, output)
    val srcToId =
      ImportCommon.checkIdMapping(inputs.srcVidAttr.rdd.map { case (k, v) => v -> k }, partitioner)
    val dstToId = {
      if (inputs.srcVidAttr.data.gUID == inputs.dstVidAttr.data.gUID)
        srcToId
      else
        ImportCommon.checkIdMapping(
          inputs.dstVidAttr.rdd.map { case (k, v) => v -> k }, partitioner)
    }
    val srcResolvedByDst = RDDUtils.hybridLookup(
      edgeSrcDst(columns).map {
        case (edgeId, (src, dst)) => src -> (edgeId, dst)
      },
      srcToId)
      .map { case (src, ((edgeId, dst), sid)) => dst -> (edgeId, sid) }

    val edges = RDDUtils.hybridLookup(srcResolvedByDst, dstToId)
      .map { case (dst, ((edgeId, sid), did)) => edgeId -> Edge(sid, did) }
      .toSortedRDD(partitioner)

    output(o.edges, edges)
  }
}

object ImportAttributesForExistingVertexSet extends OpFromJson {
  class Input extends MagicInputSignature {
    val vs = vertexSet
    val idAttr = vertexAttribute[String](vs)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input,
               fields: Set[String])
      extends MagicOutput(instance) {
    val attrs = fields.map {
      f => f -> vertexAttribute[String](inputs.vs.entity, ImportCommon.toSymbol(f))
    }.toMap
  }
  def fromJson(j: JsValue) =
    ImportAttributesForExistingVertexSet(TypedJson.read[RowInput](j \ "input"), (j \ "idField").as[String])
}
case class ImportAttributesForExistingVertexSet(input: RowInput, idField: String)
    extends ImportCommon
    with TypedMetaGraphOp[ImportAttributesForExistingVertexSet.Input, ImportAttributesForExistingVertexSet.Output] {
  import ImportAttributesForExistingVertexSet._

  mustHaveField(idField)

  override val isHeavy = true
  @transient override lazy val inputs = new Input()
  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs, input.fields.toSet - idField)
  override def toJson = Json.obj("input" -> input.toTypedJson, "idField" -> idField)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val partitioner = inputs.vs.rdd.partitioner.get
    val lines = input.lines(rc).values
    val idFieldIdx = input.fields.indexOf(idField)
    val externalIdToInternalId = ImportCommon.checkIdMapping(
      inputs.idAttr.rdd.map { case (internal, external) => (external, internal) },
      partitioner)
    val linesByExternalId = lines
      .map(line => (line(idFieldIdx), line))
      .toSortedRDD(partitioner)
    val linesByInternalId =
      linesByExternalId.sortedJoin(externalIdToInternalId)
        .map { case (external, (line, internal)) => (internal, line) }
        .toSortedRDD(partitioner)
    linesByInternalId.cacheBackingArray()
    for ((field, idx) <- input.fields.zipWithIndex) {
      if (idx != idFieldIdx) {
        output(o.attrs(field), linesByInternalId.mapValues(line => line(idx)))
      }
    }
  }
}
