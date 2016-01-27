// A helper class to handle machine learning models.
package com.lynxanalytics.biggraph.model

import com.lynxanalytics.biggraph.graph_api._
import org.apache.spark.mllib
import org.apache.spark.rdd.RDD
import org.apache.spark

// A unified interface for different tpyes of MLLIB models.
trait ModelImplementation {
  def predict(data: RDD[mllib.linalg.Vector]): RDD[Double]
}

case class LinearRegressionModelImpl(m: mllib.regression.LinearRegressionModel) extends ModelImplementation {
  def predict(data: RDD[mllib.linalg.Vector]): RDD[Double] = { m.predict(data) }
}

case class Model(
    name: String,
    method: String,
    path: String,
    labelName: String,
    featureNames: List[String],
    labelScaler: Option[mllib.feature.StandardScalerModel],
    featureScaler: mllib.feature.StandardScalerModel) {

  // Loads the previously created model from the file system.
  def load(rc: RuntimeContext): ModelImplementation = {
    val sc = rc.sparkContext
    method match {
      case "Linear regression" =>
        LinearRegressionModelImpl(mllib.regression.LinearRegressionModel.load(sc, path))
    }
  }

  // Scales back the labels if needed.
  def scaleBack(result: RDD[Double]): RDD[Double] = {
    if (labelScaler.isEmpty) {
      result
    } else {
      Model.scaleBack(result, labelScaler.get)
    }
  }
}

// Helper methods to transform and scale training and prediction data.
object Model {
  def checkLinearModel(model: mllib.regression.GeneralizedLinearModel): Unit = {
    // A linear model with at least one NaN parameter will always predict NaN.
    for (w <- model.weights.toArray :+ model.intercept) {
      assert(!w.isNaN, "Failed to train a valid regression model.")
    }
  }

  // Transforms the result RDD using the inverse transformation of the original scaling.
  def scaleBack(
    result: RDD[Double],
    scaler: mllib.feature.StandardScalerModel): RDD[Double] = {
    assert(scaler.mean.size == 1)
    assert(scaler.std.size == 1)
    val mean = scaler.mean(0)
    val std = scaler.std(0)
    result.map { v => v * std + mean }
  }

  // Transforms features to an MLLIB compatible format.
  def transformFeatures(
    features: Array[AttributeRDD[Double]],
    vertices: VertexSetRDD,
    numFeatures: Int): AttributeRDD[mllib.linalg.Vector] = {
    val emptyArrays = vertices.mapValues(l => new Array[Double](numFeatures))
    val numberedFeatures = features.zipWithIndex
    val fullArrays = numberedFeatures.foldLeft(emptyArrays) {
      case (a, (f, i)) =>
        a.sortedJoin(f).mapValues {
          case (a, f) => a(i) = f; a
        }
    }
    fullArrays.mapValues(a => new mllib.linalg.DenseVector(a): mllib.linalg.Vector)
  }
}

case class FEModel(
  val name: String,
  val featureNames: List[String])

trait ModelMeta {
  def modelName: String
  def featureNames: List[String]
  def toFE: FEModel = { FEModel(modelName, featureNames) }
}

case class ScaledParams(
  // Labeled training data points.
  points: RDD[mllib.regression.LabeledPoint],
  // All feature data.
  vectors: AttributeRDD[mllib.linalg.Vector],
  // An optional scaler if it was used to scale the labels. It can be used
  // to scale back the results.
  labelScaler: Option[mllib.feature.StandardScalerModel],
  featureScaler: mllib.feature.StandardScalerModel)

case class Scaler(
    val forSGD: Boolean) { // Whether the data should be prepared for an SGD method.

  // Creates the input for training and evaluation.
  def scale(
    labelRDD: AttributeRDD[Double],
    features: Array[AttributeRDD[Double]],
    vertices: VertexSetRDD,
    numFeatures: Int)(implicit id: DataSet): ScaledParams = {

    val unscaled = Model.transformFeatures(features, vertices, numFeatures)
    // All scaled data points.
    val (vectors, featureScaler) = {

      // Must scale the features or we get NaN predictions. (SPARK-1859)
      val scaler = new mllib.feature.StandardScaler(
        withMean = forSGD, // Center the vectors for SGD training methods.
        withStd = true).fit(
        // Set the scaler based on only the training vectors, i.e. where we have a label.
        labelRDD.sortedJoin(unscaled).values.map {
          case (_, v) => v
        })
      // Scale all vectors using the scaler created from the training vectors.
      (unscaled.mapValues(v => scaler.transform(v)), scaler)
    }

    val (labels, labelScaler) = if (forSGD) {
      // For SGD methods the labels need to be scaled too. Otherwise the optimal
      // stepSize can vary greatly.
      val labelVector = labelRDD.mapValues {
        a => new mllib.linalg.DenseVector(Array(a)): mllib.linalg.Vector
      }
      val labelScaler = new mllib.feature.StandardScaler(withMean = true, withStd = true)
        .fit(labelVector.values)
      (labelVector.mapValues(v => labelScaler.transform(v)(0)), Some(labelScaler))
    } else {
      (labelRDD, None)
    }

    val points = labels.sortedJoin(vectors).values.map {
      case (l, v) => new mllib.regression.LabeledPoint(l, v)
    }
    points.cache
    ScaledParams(points, vectors, labelScaler, featureScaler)
  }
}
