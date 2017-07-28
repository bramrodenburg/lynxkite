package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.controllers._

class ExportOperations(env: SparkFreeEnvironment) extends OperationRegistry {
  implicit lazy val manager = env.metaGraphManager

  override val defaultIcon = "black_truck"

  import Operation.Context

  import Categories.ExportOperations

  def register(id: String)(factory: Context => ExportOperation): Unit = {
    registerOp(id, defaultIcon, ExportOperations, List("table"), List("exported"), factory)
  }

  import OperationParams._

  register("Export to CSV")(new ExportOperationToFile(_) {
    lazy val format = "csv"
    params ++= List(
      Param("path", "Path", defaultValue = "<auto>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param("quote", "Quote", defaultValue = ""),
      Choice("header", "Include header", FEOption.list("yes", "no")),
      NonNegInt("version", "Version", default = 0))

    def exportResult() = {
      val header = if (params("header") == "yes") true else false
      val path = generatePathIfNeeded(params("path"))
      val op = graph_operations.ExportTableToCSV(
        path, header,
        params("delimiter"), params("quote"),
        params("version").toInt
      )
      op(op.t, table).result.exportResult
    }
  })

  register("Export to JDBC")(new ExportOperation(_) {
    lazy val format = "jdbc"
    params ++= List(
      Param("jdbc_url", "JDBC URL"),
      Param("jdbc_table", "Table"),
      Choice("mode", "Mode", FEOption.list(
        "The table must not exist",
        "Drop the table if it already exists",
        "Insert into an existing table")))

    def exportResult() = {
      val mode = params("mode") match {
        case "The table must not exist" => "error"
        case "Drop the table if it already exists" => "overwrite"
        case "Insert into an existing table" => "append"
      }
      val op = graph_operations.ExportTableToJdbc(
        params("jdbc_url"),
        params("jdbc_table"),
        mode
      )
      op(op.t, table).result.exportResult
    }
  })

  registerExportToStructuredFile("Export to JSON")("json")
  registerExportToStructuredFile("Export to Parquet")("parquet")
  registerExportToStructuredFile("Export to ORC")("orc")

  def registerExportToStructuredFile(id: String)(fileFormat: String) {
    register(id)(new ExportOperationToFile(_) {
      lazy val format = fileFormat
      params ++= List(
        Param("path", "Path", defaultValue = "<auto>"),
        NonNegInt("version", "Version", default = 0))

      val path = generatePathIfNeeded(params("path"))
      def exportResult = {
        val op = graph_operations.ExportTableToStructuredFile(
          path, format, params("version").toInt
        )
        op(op.t, table).result.exportResult
      }
    })
  }
}

