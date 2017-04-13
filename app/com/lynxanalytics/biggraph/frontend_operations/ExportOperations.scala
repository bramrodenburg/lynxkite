package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.graph_util.JDBCUtil
import com.lynxanalytics.biggraph.graph_util.Scripting._
import com.lynxanalytics.biggraph.controllers._
import com.lynxanalytics.biggraph.graph_util.LoggedEnvironment
import com.lynxanalytics.biggraph.model
import com.lynxanalytics.biggraph.serving.FrontendJson
import play.api.libs.json

class ExportOperations(env: SparkFreeEnvironment) extends OperationRegistry {
  implicit lazy val manager = env.metaGraphManager

  import Operation.Category
  import Operation.Context
  import Operation.Implicits._

  private val tableInput = "table"
  private val exportResultConnection = TypedConnection("result", BoxOutputKind.ExportResult)
  val ExportOperations = Category("Export operations", "blue", icon = "folder-open")

  def register(id: String)(factory: Context => ExportOperation): Unit = {
    registerOp(id, ExportOperations, List(tableInput), List(exportResultConnection), factory)
  }

  import OperationParams._
  import org.apache.spark

  register("Export to CSV")(new ExportOperation(_) {
    lazy val parameters = List(
      Param("path", "Path", defaultValue = "<download>"),
      Param("delimiter", "Delimiter", defaultValue = ","),
      Param("quote", "Quote", defaultValue = ""),
      Choice("header", "Strip header", FEOption.list("no", "yes")),
      NonNegInt("version", "Version", default = 0)
    )

    def apply() = {
      val header = if (params("header") == "no") true else false
      val op = graph_operations.ExportTableToFlatFile(
        params("path"), header,
        params("delimiter"), params("quote"),
        params("version").toInt
      )
      exportResult = Some(op(op.t, table).result.exportResult)
    }
  })
}

