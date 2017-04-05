package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.controllers.ExportResult
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_util.{HadoopFile, Timestamp}

object ExportTableToFile {
  class Input extends MagicInputSignature {
    val t = table
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val exportResult = scalar[ExportResult]
  }

  def getFile(format: String, path: String)(implicit dataManager: DataManager) = {
    if (path == "<download>") {
      dataManager.repositoryPath / "exports" / Timestamp.toString + "." + format
    } else {
      HadoopFile(path)
    }
  }

  def run(format: String, path: String) = {}
}

import ExportTableToFile._
abstract class ExportTableToFile extends TypedMetaGraphOp[Input, Output] {
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)
}

object ExportTableToFlatFile extends OpFromJson {
  def fromJson(j: JsValue) = ExportTableToFlatFile(
    (j \ "path").as[String], (j \ "header").as[Boolean],
    (j \ "delimiter").as[String], (j \ "quote").as[String])
}

case class ExportTableToFlatFile(path: String, header: Boolean,
                                 delimiter: String, quote: String) extends ExportTableToFile {
  override def toJson = Json.obj(
    "path" -> path, "header" -> header,
    "delimiter" -> delimiter, "quote" -> quote)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val ds = inputDatas
    implicit val dataManager = rc.dataManager
    val file = getFile("csv", path)
    val df = inputs.t.df
    val options = Map(
      "delimiter" -> delimiter,
      "quote" -> quote,
      "nullvalue" -> "",
      "header" -> (if (header) "true" else "false"))
    )
    df.write.format("csv").options(options).save(file.resolvedName)
    val numberOfRows = df.count
    val exportResult = ExportResult(numberOfRows, "csv", file.resolvedName)
    output(o.exportResult, exportResult)
  }
}

case class ExportTableToStructuredFile(path: String, format: String)
  extends ExportTableToFile {

  override def toJson = Json.obj(
    "path" -> path, "format" -> format)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val ds = inputDatas
    implicit val dataManager = rc.dataManager
    val file = getFile(format, path)
    val df = inputs.t.df
    df.write.format(format).save(file.resolvedName)
    val numberOfRows = df.count
    val exportResult = ExportResult(numberOfRows, format, file.resolvedName)
    output(o.exportResult, exportResult)
  }
}
