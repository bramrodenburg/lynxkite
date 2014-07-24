package com.lynxanalytics.biggraph.graph_operations

import scala.util.Random

import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object SimpleRandomEdgeBundle {
  class Input extends MagicInputSignature {
    val vsSrc = vertexSet
    val vsDst = vertexSet
  }
  class Output(implicit instance: MetaGraphOperationInstance, inputs: Input)
      extends MagicOutput(instance) {
    val es = edgeBundle(inputs.vsSrc.entity, inputs.vsDst.entity)
  }
}
import SimpleRandomEdgeBundle._
case class SimpleRandomEdgeBundle(seed: Int, density: Float) extends TypedMetaGraphOp[Input, Output] {
  @transient override lazy val inputs = new Input

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val allEdges = inputs.vsSrc.rdd.cartesian(inputs.vsDst.rdd)

    val randomEdges = allEdges.mapPartitionsWithIndex {
      case (pidx, it) =>
        val rand = new Random((pidx << 16) + seed)
        it.filter(_ => rand.nextFloat < density)
          .map { case ((srcId, _), (dstId, _)) => Edge(srcId, dstId) }
    }

    output(o.es, randomEdges.fastNumbered(rc.defaultPartitioner))
  }
}
