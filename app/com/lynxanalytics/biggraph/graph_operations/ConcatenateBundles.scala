package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object ConcatenateBundles {
  class Input extends MagicInputSignature {
    val vsA = vertexSet
    val vsB = vertexSet
    val vsC = vertexSet
    val edgesAB = edgeBundle(vsA, vsB)
    val edgesBC = edgeBundle(vsB, vsC)
    val weightsAB = edgeAttribute[Double](edgesAB)
    val weightsBC = edgeAttribute[Double](edgesBC)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val edgesAC = edgeBundle(inputs.vsA.entity, inputs.vsC.entity)
    val weightsAC = edgeAttribute[Double](edgesAC)
  }
}
import ConcatenateBundles._
case class ConcatenateBundles() extends TypedMetaGraphOp[Input, Output] {
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

  override val isHeavy = true
}
