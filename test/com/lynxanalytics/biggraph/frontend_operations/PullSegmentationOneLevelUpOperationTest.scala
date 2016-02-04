package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_api.GraphTestUtils._

class CopySegmentationOneLevelUpOperationTest extends OperationsTestBase {

  test("Pull segmentation one level up") {
    run("Import vertices", Map(
      "table" -> importCSV("OPERATIONSTEST$/copy-segmentation-one-level-up-vertices.csv"),
      "id-attr" -> "id"))
    run("Import segmentation", Map(
      "table" -> importCSV("OPERATIONSTEST$/copy-segmentation-one-level-up-connections.csv"),
      "name" -> "segmentation1",
      "base-id-attr" -> "num",
      "base-id-column" -> "base_num",
      "seg-id-column" -> "seg_num"))
    val segmentation1 = project.segmentation("segmentation1")
    run("Import segmentation", Map(
      "table" -> importCSV("OPERATIONSTEST$/copy-segmentation-one-level-up-connections.csv"),
      "name" -> "segmentation2",
      "base-id-attr" -> "seg_num",
      "base-id-column" -> "base_num",
      "seg-id-column" -> "seg_num"),
      on = segmentation1)
    val segmentation2 = segmentation1.segmentation("segmentation2")

    run("Pull segmentation one level up", on = segmentation2)

    assert(project.segmentationNames.contains("segmentation2"))
    val segmentation2Copy = project.segmentation("segmentation2")
    val segmentSizes = segmentation2Copy
      .belongsTo
      .toPairSeq()
      .groupBy(_._1)
      .values
      .map(_.size)
      .toSeq
      .sorted
    assert(segmentSizes == Seq(1, 2, 3, 3, 3))
  }
}
