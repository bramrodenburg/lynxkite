// Trains a linear regression model.
package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.model._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import org.apache.spark.mllib
import org.apache.spark.rdd

object RegressionModelTrainer extends OpFromJson {
  class Input(numFeatures: Int) extends MagicInputSignature {
    val vertices = vertexSet
    val features = (0 until numFeatures).map {
      i => vertexAttribute[Double](vertices, Symbol(s"feature-$i"))
    }
    val label = vertexAttribute[Double](vertices)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val model = scalar[Model]
  }
  def fromJson(j: JsValue) = RegressionModelTrainer(
    (j \ "method").as[String],
    (j \ "labelName").as[String],
    (j \ "featureNames").as[List[String]])
}
import RegressionModelTrainer._
case class RegressionModelTrainer(
    method: String,
    labelName: String,
    featureNames: List[String]) extends TypedMetaGraphOp[Input, Output] with ModelMeta {
  @transient override lazy val inputs = new Input(featureNames.size)
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj(
    "method" -> method,
    "labelName" -> labelName,
    "featureNames" -> featureNames)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val p = new Scaler(forSGD = true).scale(
      inputs.label.rdd,
      inputs.features.toArray.map { v => v.rdd },
      inputs.vertices.rdd)

    val model = method match {
      case "Linear regression" =>
        new mllib.regression.LinearRegressionWithSGD().setIntercept(true).run(p.points)
      case "Ridge regression" =>
        new mllib.regression.RidgeRegressionWithSGD().setIntercept(true).run(p.points)
      case "Lasso" =>
        new mllib.regression.LassoWithSGD().setIntercept(true).run(p.points)
    }
    Model.checkLinearModel(model)

    val path = Model.newModelPath
    model.save(rc.sparkContext, path)
    output(o.model, Model(
      method = method,
      path = path,
      labelName = labelName,
      featureNames = featureNames,
      labelScaler = p.labelScaler,
      featureScaler = p.featureScaler))
  }
}
