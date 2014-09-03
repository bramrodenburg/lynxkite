package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.apache.spark.rdd.RDD
import scala.reflect.runtime.universe._

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util.SortedRDD

object VertexSetUnion {
  class Input(numVertexSets: Int) extends MagicInputSignature {
    val vss = Range(0, numVertexSets).map {
      i => vertexSet(Symbol("vs" + i))
    }.toList
  }
  class Output(numVertexSets: Int)(
      implicit instance: MetaGraphOperationInstance,
      input: Input) extends MagicOutput(instance) {

    val union = vertexSet
    // Injections of the original vertex sets into the union.
    val injections = Range(0, numVertexSets)
      .map(i => edgeBundle(
        input.vss(i).entity, union, EdgeBundleProperties.injection, name = Symbol("injection" + i)))
  }
}
case class VertexSetUnion(numVertexSets: Int)
    extends TypedMetaGraphOp[VertexSetUnion.Input, VertexSetUnion.Output] {

  import VertexSetUnion._
  assert(numVertexSets >= 1)
  @transient override lazy val inputs = new Input(numVertexSets)

  def outputMeta(instance: MetaGraphOperationInstance) = new Output(numVertexSets)(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val unionWithOldIds = rc.sparkContext.union(
      inputs.vss
        .map(_.rdd)
        .zipWithIndex
        .map { case (rdd, idx) => rdd.mapValues(_ => idx) }).randomNumbered(rc.defaultPartitioner)
    output(o.union, unionWithOldIds.mapValues(_ => ()))
    for (idx <- 0 until numVertexSets) {
      output(
        o.injections(idx),
        unionWithOldIds
          .filter { case (newId, (oldId, sourceIdx)) => sourceIdx == idx }
          .map { case (newId, (oldId, sourceIdx)) => Edge(oldId, newId) }
          .randomNumbered(rc.defaultPartitioner))
    }
  }
}

object EdgeBundleUnion {
  class Input(numEdgeBundles: Int) extends MagicInputSignature {
    val src = vertexSet
    val dst = vertexSet
    val idSets = Range(0, numEdgeBundles).map {
      i => vertexSet(Symbol("id_set" + i))
    }.toList
    val idSetUnion = vertexSet
    val injections = Range(0, numEdgeBundles).map {
      i =>
        edgeBundle(
          idSets(i),
          idSetUnion,
          requiredProperties = EdgeBundleProperties.injection,
          name = Symbol("injection" + i))
    }.toList
    val ebs = Range(0, numEdgeBundles).map {
      i => edgeBundle(src, dst, idSet = idSets(i), name = Symbol("eb" + i))
    }.toList
  }
  class Output(numEdgeBundles: Int)(
      implicit instance: MetaGraphOperationInstance,
      input: Input) extends MagicOutput(instance) {
    val union = edgeBundle(input.src.entity, input.dst.entity, idSet = input.idSetUnion.entity)
  }
}
case class EdgeBundleUnion(numEdgeBundles: Int)
    extends TypedMetaGraphOp[EdgeBundleUnion.Input, EdgeBundleUnion.Output] {

  import EdgeBundleUnion._
  assert(numEdgeBundles >= 1)
  @transient override lazy val inputs = new Input(numEdgeBundles)

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output(numEdgeBundles)(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val idSetUnion = inputs.idSetUnion.rdd
    val reIDedEbs = Range(0, numEdgeBundles).map { i =>
      val eb = inputs.ebs(i).rdd
      // Here typically none of the injections are identity, so we don't special case for that.
      val injection =
        inputs.injections(i).rdd
          .map { case (_, e) => e.src -> e.dst }
          .toSortedRDD(eb.partitioner.get)
      eb.sortedJoin(injection).map { case (oldId, (edge, newId)) => (newId, edge) }
    }
    output(o.union, rc.sparkContext.union(reIDedEbs).toSortedRDD(idSetUnion.partitioner.get))
  }
}
