package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object PulledOverEdges {
  class Input extends MagicInputSignature {
    val originalSrc = vertexSet
    val originalDst = vertexSet
    val originalIDs = vertexSet
    val originalEB = edgeBundle(originalSrc, originalDst, idSet = originalIDs)
    val destinationVS = vertexSet
    val injection = edgeBundle(destinationVS, originalIDs, EdgeBundleProperties.injection)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val pulledEB = edgeBundle(
      inputs.originalSrc.entity,
      inputs.originalDst.entity,
      idSet = inputs.destinationVS.entity)
  }
}
import PulledOverEdges._
case class PulledOverEdges()
    extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val injectionEntity = inputs.injection.meta
    val injection = inputs.injection.rdd
    val originalEB = inputs.originalEB.rdd
    val destinationVS = inputs.destinationVS.rdd
    val pulledEB =
      if (injectionEntity.properties.isIdentity) {
        originalEB.sortedJoin(destinationVS).mapValues { case (edge, _) => edge }
      } else {
        val originalToDestinationID = injection
          .map { case (id, edge) => (edge.dst, edge.src) }
          .toSortedRDD(destinationVS.partitioner.get)
        originalEB.sortedJoin(originalToDestinationID)
          .map { case (originalID, (edge, destinationID)) => (destinationID, edge) }
          .toSortedRDD(destinationVS.partitioner.get)
      }
    output(o.pulledEB, pulledEB)
  }
}
