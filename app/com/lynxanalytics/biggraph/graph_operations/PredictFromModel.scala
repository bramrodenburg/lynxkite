package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.model._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import org.apache.spark.mllib
import org.apache.spark.rdd

object PredictFromModel extends OpFromJson {
  class Input(numFeatures: Int) extends MagicInputSignature {
    val vertices = vertexSet
    val features = (0 until numFeatures).map {
      i => vertexAttribute[Double](vertices, Symbol(s"feature-$i"))
    }
    val model = scalar[Model]
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val prediction = vertexAttribute[Double](inputs.vertices.entity)
  }
  def fromJson(j: JsValue) = PredictFromModel((j \ "numFeatures").as[Int])
}
import PredictFromModel._
case class PredictFromModel(numFeatures: Int)
    extends TypedMetaGraphOp[Input, Output] {
  @transient override lazy val inputs = new Input(numFeatures)
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj("numFeatures" -> numFeatures)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val model = inputs.model.value
    val scaled = Model.unscaledFeatures(
      inputs.features.toArray.map { v => v.rdd },
      inputs.vertices.rdd,
      numFeatures).mapValues(v => model.featureScaler.transform(v))

    val predictions = model.scaleBack(model.model(rc).predict(scaled.values))
    val ids = scaled.keys // We just put back the keys with a zip.
    output(
      o.prediction,
      ids.zip(predictions).filter(!_._2.isNaN).asUniqueSortedRDD(scaled.partitioner.get))
  }
}
