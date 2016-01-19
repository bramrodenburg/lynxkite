// Creates edges that connect vertices that have the same value for the given attribute.
package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import org.apache.spark.Partitioner

object EdgesFromAttributeMatches extends OpFromJson {
  class Output[T](implicit instance: MetaGraphOperationInstance, inputs: VertexAttributeInput[T])
      extends MagicOutput(instance) {
    val edges = edgeBundle(inputs.vs.entity, inputs.vs.entity)
  }
  def fromJson(j: JsValue) = EdgesFromAttributeMatches()
}
case class EdgesFromAttributeMatches[T]() extends TypedMetaGraphOp[VertexAttributeInput[T], EdgesFromAttributeMatches.Output[T]] {
  import EdgesFromAttributeMatches._
  override val isHeavy = true
  @transient override lazy val inputs = new VertexAttributeInput[T]
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output[T],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val ct = inputs.attr.data.classTag
    val attr = inputs.attr.rdd
    val byAttr = attr.map { case (id, attr) => (attr, id) }
    val matching = byAttr.groupByKey()
    val edges = matching.flatMap {
      case (attr, vertices) => for { a <- vertices; b <- vertices; if a != b } yield Edge(a, b)
    }
    output(o.edges, edges.randomNumbered(attr.partitioner.get))
  }
}

// Generates edges between vertices that match on an attribute.
// If fromAttr on A matches toAttr on B, an A -> B edge is generated.
object EdgesFromBipartiteAttributeMatches extends OpFromJson {
  class Input[T] extends MagicInputSignature {
    val from = vertexSet
    val to = vertexSet
    val fromAttr = vertexAttribute[T](from)
    val toAttr = vertexAttribute[T](to)
  }
  class Output[T](implicit instance: MetaGraphOperationInstance, inputs: Input[T])
      extends MagicOutput(instance) {
    val edges = edgeBundle(inputs.from.entity, inputs.to.entity)
  }
  def fromJson(j: JsValue) = EdgesFromBipartiteAttributeMatches()
  def getLargerPartitioner(p1: Partitioner, p2: Partitioner): Partitioner =
    if (p1.numPartitions > p2.numPartitions) p1
    else p2
}
case class EdgesFromBipartiteAttributeMatches[T]()
    extends TypedMetaGraphOp[EdgesFromBipartiteAttributeMatches.Input[T], EdgesFromBipartiteAttributeMatches.Output[T]] {
  import EdgesFromBipartiteAttributeMatches._
  override val isHeavy = true
  @transient override lazy val inputs = new Input[T]
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output[T],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val ct = inputs.fromAttr.data.classTag
    val fromByAttr = inputs.fromAttr.rdd
      .map { case (id, attr) => (attr, id) }
      .groupByKey()
    val toByAttr = inputs.toAttr.rdd
      .map { case (id, attr) => (attr, id) }
      .groupByKey()
    val edges = fromByAttr.join(toByAttr).flatMap {
      case (attr, (fromIds, toIds)) => for { a <- fromIds; b <- toIds } yield Edge(a, b)
    }
    val partitioner = getLargerPartitioner(
      inputs.from.rdd.partitioner.get,
      inputs.to.rdd.partitioner.get)
    output(o.edges, edges.randomNumbered(partitioner))
  }
}

// Generates edges between vertices that match on an attribute.
// If fromAttr on A matches toAttr on B, an A -> B edge is generated.
// Is is assumed that the values of both fromAttr and toAttr are unique.
// For attribute values with multiplicity over one, some edges will be created
// but the output is not defined. (See the implementation of sortedJoin.)
object EdgesFromUniqueBipartiteAttributeMatches extends OpFromJson {
  class Input extends MagicInputSignature {
    val from = vertexSet
    val fromAttr = vertexAttribute[String](from)
    val to = vertexSet
    val toAttr = vertexAttribute[String](to)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input)
      extends MagicOutput(instance) {
    val edges = edgeBundle(
      inputs.from.entity,
      inputs.to.entity,
      EdgeBundleProperties.partialFunction)
  }
  def fromJson(j: JsValue) =
    EdgesFromUniqueBipartiteAttributeMatches()
}
case class EdgesFromUniqueBipartiteAttributeMatches()
    extends TypedMetaGraphOp[EdgesFromUniqueBipartiteAttributeMatches.Input, EdgesFromUniqueBipartiteAttributeMatches.Output] {
  import EdgesFromUniqueBipartiteAttributeMatches._

  override val isHeavy = true
  @transient override lazy val inputs = new Input()
  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val partitioner = EdgesFromBipartiteAttributeMatches.getLargerPartitioner(
      inputs.from.rdd.partitioner.get,
      inputs.to.rdd.partitioner.get)
    val fromStringToId = inputs.fromAttr.rdd
      .map { case (fromId, fromString) => (fromString, fromId) }
      .checkIdMapping(partitioner)
    val toStringToId = inputs.toAttr.rdd
      .map { case (toId, toString) => (toString, toId) }
      .checkIdMapping(partitioner)
    val mapping = fromStringToId
      .sortedJoin(toStringToId)
      .values
      .map { case (fromId, toId) => (fromId, Edge(fromId, toId)) }
      .sortUnique(partitioner)

    output(o.edges, mapping)
  }
}
