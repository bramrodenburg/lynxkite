package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import scala.collection.mutable

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_util._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util.RDDUtils

object AttributeHistogram {
  class Input[T] extends MagicInputSignature {
    val original = vertexSet
    val filtered = vertexSet
    val attr = vertexAttribute[T](original)
    val originalCount = scalar[Long]
  }
  class Output(implicit instance: MetaGraphOperationInstance) extends MagicOutput(instance) {
    val counts = scalar[Map[Int, Long]]
  }
}
import AttributeHistogram._
case class AttributeHistogram[T](bucketer: Bucketer[T])
    extends TypedMetaGraphOp[Input[T], Output] {
  @transient override lazy val inputs = new Input[T]

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val instance = output.instance
    val attrMeta = inputs.attr.meta
    implicit val ct = attrMeta.classTag
    val filteredAttr = inputs.attr.rdd.sortedJoin(inputs.filtered.rdd)
      .mapValues { case (value, _) => value }
    val bucketedAttr = filteredAttr.mapValues(bucketer.whichBucket(_))

    output(
      o.counts,
      RDDUtils.estimateValueCounts(
        inputs.original.rdd,
        bucketedAttr,
        inputs.originalCount.value,
        50000).mapValues(_.count))
  }
}
