// These tests check some lower level infrastructure beneath
// various operations. So, there is no single operation this class
// is revolving around.
package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.controllers._

class AuxiliaryOperationTest extends OperationsTestBase {

  test("Optional and mandatory parameters work") {
    val project = box("Create example graph")
      .box("Aggregate edge attribute to vertices", Map(
        "prefix" -> "incoming",
        "direction" -> "incoming edges",
        // "aggregate_comment" -> "", This is now optional
        "aggregate_weight" -> "sum"))
      .box("Aggregate edge attribute to vertices", Map(
        "prefix" -> "incoming",
        // "direction" -> "incoming edges", But this is not
        "aggregate_comment" -> "",
        "aggregate_weight" -> "sum"))
    intercept[java.lang.AssertionError] {
      project.enforceComputation
    }
  }
}

