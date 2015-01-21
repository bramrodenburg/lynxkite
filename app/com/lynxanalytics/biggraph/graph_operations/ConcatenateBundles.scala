package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object ConcatenateBundles extends OpFromJson {
  class Input extends MagicInputSignature {
    val vsA = vertexSet
    val vsB = vertexSet
    val vsC = vertexSet
    val idsAB = vertexSet
    val idsBC = vertexSet
    val edgesAB = edgeBundle(vsA, vsB, idSet = idsAB)
    val edgesBC = edgeBundle(vsB, vsC, idSet = idsBC)
    val weightsAB = vertexAttribute[Double](idsAB)
    val weightsBC = vertexAttribute[Double](idsBC)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val isFunction =
      inputs.edgesAB.properties.isFunction && inputs.edgesBC.properties.isFunction
    val isReversedFunction =
      inputs.edgesAB.properties.isReversedFunction && inputs.edgesBC.properties.isReversedFunction
    val edgesAC = edgeBundle(
      inputs.vsA.entity,
      inputs.vsC.entity,
      EdgeBundleProperties(isFunction = isFunction, isReversedFunction = isReversedFunction))
    val weightsAC = vertexAttribute[Double](edgesAC.asVertexSet)
  }
  def fromJson(j: play.api.libs.json.JsValue) = ConcatenateBundles()
}
import ConcatenateBundles._
case class ConcatenateBundles() extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val edgesAB = inputs.edgesAB.rdd
    val edgesBC = inputs.edgesBC.rdd
    val weightsAB = inputs.weightsAB.rdd
    val weightsBC = inputs.weightsBC.rdd
    val weightedEdgesAB = edgesAB.sortedJoin(weightsAB)
    val weightedEdgesBC = edgesBC.sortedJoin(weightsBC)

    val partitioner = inputs.vsB.rdd.partitioner.get
    val BA = weightedEdgesAB.map { case (_, (edge, weight)) => edge.dst -> (edge.src, weight) }.toSortedRDD(partitioner)
    val BC = weightedEdgesBC.map { case (_, (edge, weight)) => edge.src -> (edge.dst, weight) }.toSortedRDD(partitioner)

    // can't use sortedJoin as we need to provide cartesian product for many to many relations
    val AC = BA.join(BC).map {
      case (_, ((vertexA, weightAB), (vertexC, weightBC))) => (Edge(vertexA, vertexC), weightAB * weightBC)
    }.reduceByKey(_ + _) // TODO: possibility to define arbitrary concat functions as JS

    val numberedAC = AC.randomNumbered(rc.defaultPartitioner)

    output(o.edgesAC, numberedAC.mapValues { case (edge, weight) => edge })
    output(o.weightsAC, numberedAC.mapValues { case (edge, weight) => weight })
  }
}
