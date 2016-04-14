// Merges vertices that match on an attribute.
package com.lynxanalytics.biggraph.graph_operations

import scala.util.Random
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object MergeVertices extends OpFromJson {
  class Output(
      implicit instance: MetaGraphOperationInstance,
      inputs: VertexAttributeInput[_]) extends MagicOutput(instance) {

    val segments = vertexSet
    val belongsTo = edgeBundle(inputs.vs.entity, segments, EdgeBundleProperties.partialFunction)
    val representative = edgeBundle(segments, inputs.vs.entity, EdgeBundleProperties.embedding)
  }
  def fromJson(j: JsValue) = MergeVertices()
}
import MergeVertices._
case class MergeVertices[T]() extends TypedMetaGraphOp[VertexAttributeInput[T], Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new VertexAttributeInput[T]
  def outputMeta(instance: MetaGraphOperationInstance) = {
    new Output()(instance, inputs)
  }

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val ct = inputs.attr.data.classTag
    val attr = inputs.attr.rdd
    val partitioner = attr.partitioner.get
    val byAttr = attr.map(_.swap)
    val matching = byAttr
      .groupByKey(partitioner)
      .mapPartitionsWithIndex {
        case (pid, it) =>
          val rnd = new Random(pid)
          it.map {
            case (attr, vertices) =>
              (vertices.drop(rnd.nextInt(vertices.size)).head, vertices)
          }
      }
      .sortUnique(partitioner)
    output(o.segments, matching.mapValues(_ => ()))
    output(o.representative, matching.mapValuesWithKeys { case (key, _) => Edge(key, key) })
    output(
      o.belongsTo,
      matching.flatMap {
        case (groupId, vertices) =>
          vertices.map(v => v -> Edge(v, groupId))
      }.sortUnique(partitioner))
  }
}
