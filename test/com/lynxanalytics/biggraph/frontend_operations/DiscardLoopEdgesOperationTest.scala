package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.graph_api.Scripting._

class DiscardLoopEdgesOperationTest extends OperationsTestBase {
  test("Discard loop edges") {
    val imported = importBox("Import CSV", Map("filename" -> "OPERATIONSTEST$/loop-edges.csv"))
      .box("Import vertices and edges from a single table", Map(
        "src" -> "src",
        "dst" -> "dst"))
    val discarded = imported.box("Discard loop edges")
    def colors(box: TestBox) =
      box.project.edgeAttributes("color").runtimeSafeCast[String].rdd.values.collect.toSeq.sorted
    assert(colors(imported) == Seq("blue", "green", "red"))
    assert(colors(discarded) == Seq("blue", "green")) // "red" was the loop edge.
  }
}
