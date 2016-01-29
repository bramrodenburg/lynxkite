package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.model._

class PredictFromModelTest extends ModelTestBase {
  def checkModel(method: String) {
    val g = graph(4)
    val m = model(
      method = method,
      labelName = "age",
      label = Map(0 -> 25, 1 -> 40, 2 -> 30, 3 -> 60),
      featureNames = List("yob"),
      attrs = Seq(Map(0 -> 1990, 1 -> 1975, 2 -> 1985, 3 -> 1955)),
      graph = g)

    val yob = Seq(AddDoubleVertexAttribute.run(g.vs, Map(0 -> 2000)))
    val age = predict(m, yob).rdd.values.collect()(0)
    assertRoughlyEquals(age, 15, 1)
  }

  test("test prediction from model") {
    checkModel("Linear regression")
    checkModel("Ridge regression")
    checkModel("Lasso")
  }
}
