// A base class for model related tests with utility methods.
package com.lynxanalytics.biggraph.graph_operations

import org.scalatest.FunSuite

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.model._

import org.apache.spark.mllib
import org.apache.spark.rdd

class ModelTestBase extends FunSuite with TestGraphOp {
  def model(
    method: String,
    labelName: String,
    label: Map[Int, Double],
    featureNames: List[String],
    attrs: Seq[Map[Int, Double]],
    graph: SmallTestGraph.Output): Scalar[Model] = {
    val l = AddDoubleVertexAttribute.run(graph.vs, label)
    val a = attrs.map(attr => AddDoubleVertexAttribute.run(graph.vs, attr))
    val op = RegressionModelTrainer(method, labelName, featureNames)
    op(op.features, a)(op.label, l).result.model
  }

  def predict(m: Scalar[Model], features: Seq[Attribute[Double]]): Attribute[Double] = {
    val op = PredictFromModel(features.size)
    op(op.model, m)(op.features, features).result.prediction
  }

  def graph(numVertices: Int): SmallTestGraph.Output = {
    SmallTestGraph((0 until numVertices).map(v => v -> Seq()).toMap).result
  }

  def assertRoughlyEquals(x: Double, y: Double, maxDifference: Double): Unit = {
    assert(Math.abs(x - y) < maxDifference, s"$x does not equal to $y with $maxDifference precision")
  }

  def vectorRDD(v: Array[Double]): rdd.RDD[mllib.linalg.Vector] = {
    sparkContext.parallelize(Array(new mllib.linalg.DenseVector(v)))
  }
}
