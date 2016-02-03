package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.controllers.DirectoryEntry
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_api.GraphTestUtils._
import com.lynxanalytics.biggraph.table.TableImport

class ImportSegmentationOperationTest extends OperationsTestBase {

  def getTable = {
    val rows = Seq(
      ("Adam", "Good"),
      ("Eve", "Naughty"),
      ("Bob", "Good"),
      ("Isolated Joe", "Naughty"),
      ("Isolated Joe", "Retired"))
    val sql = cleanDataManager.newSQLContext
    val dataFrame = sql.createDataFrame(rows).toDF("base_name", "seg_name")
    val table = TableImport.importDataFrameAsync(dataFrame)
    val tableFrame = DirectoryEntry.fromName("test_segmentation_import").asNewTableFrame(table, "")
    s"!checkpoint(${tableFrame.checkpoint}, ${tableFrame.name})|vertices"
  }

  test("Import segmentation for example graph") {
    run("Example Graph")
    run("Import segmentation", Map(
      "table" -> getTable,
      "name" -> "imported",
      "base-id-attr" -> "name",
      "base-id-column" -> "base_name",
      "seg-id-column" -> "seg_name"))
    checkAssertions()
  }

  test("Import segmentation links for example graph") {
    run("Example Graph")
    run("Import segmentation", Map(
      "table" -> getTable,
      "name" -> "imported",
      "base-id-attr" -> "name",
      "base-id-column" -> "base_name",
      "seg-id-column" -> "seg_name"))
    val seg = project.segmentation("imported")
    // Overwrite the links by importing them for the existing base+segmentation.
    run("Import segmentation links", Map(
      "table" -> getTable,
      "base-id-attr" -> "name",
      "seg-id-attr" -> "seg_name",
      "base-id-column" -> "base_name",
      "seg-id-column" -> "seg_name"), on = seg)
    checkAssertions()
  }

  def checkAssertions() = {
    val seg = project.segmentation("imported")
    val belongsTo = seg.belongsTo.toPairSeq
    assert(belongsTo.size == 5)
    val segNames = seg.vertexAttributes("seg_name").runtimeSafeCast[String].rdd.collect.toSeq
    assert(segNames.length == 3)
    val segMap = {
      val nameMap = segNames.toMap
      belongsTo.map { case (vid, sid) => vid -> nameMap(sid) }
    }
    assert(segMap == Seq(0 -> "Good", 1 -> "Naughty", 2 -> "Good", 3 -> "Retired", 3 -> "Naughty"))
  }
}
