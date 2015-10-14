// Transforms an edge bundle from one pair of vertex sets to another.
//
// The purpose of this is to update the edges after an operation has modified
// the vertex set. For example after filtering the vertices the edges that
// belonged to discarded vertices need to be discarded as well. You create an
// InducedEdgeBundle that follows the mapping from the unfiltered vertex set
// to the filtered one.

package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.Partitioner

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util.SortedRDD

object InducedEdgeBundle extends OpFromJson {
  class Input(induceSrc: Boolean, induceDst: Boolean) extends MagicInputSignature {
    val src = vertexSet
    val dst = vertexSet
    val srcImage = if (induceSrc) vertexSet else null
    val dstImage = if (induceDst) vertexSet else null
    val srcMapping =
      if (induceSrc) edgeBundle(src, srcImage) else null
    val dstMapping =
      if (induceDst) edgeBundle(dst, dstImage) else null
    val edges = edgeBundle(src, dst)
  }
  class Output(induceSrc: Boolean, induceDst: Boolean)(implicit instance: MetaGraphOperationInstance, inputs: Input) extends MagicOutput(instance) {
    private val srcMappingProp =
      if (induceSrc) inputs.srcMapping.entity.properties
      else EdgeBundleProperties.identity
    private val dstMappingProp =
      if (induceDst) inputs.dstMapping.entity.properties
      else EdgeBundleProperties.identity
    val induced = {
      val src = if (induceSrc) inputs.srcImage else inputs.src
      val dst = if (induceDst) inputs.dstImage else inputs.dst
      val origProp = inputs.edges.entity.properties
      val inducedProp = EdgeBundleProperties(
        isFunction =
          origProp.isFunction && srcMappingProp.isReversedFunction && dstMappingProp.isFunction,
        isReversedFunction =
          origProp.isReversedFunction && srcMappingProp.isFunction && dstMappingProp.isReversedFunction,
        isEverywhereDefined =
          origProp.isEverywhereDefined && srcMappingProp.isReverseEverywhereDefined,
        isReverseEverywhereDefined =
          origProp.isReverseEverywhereDefined && dstMappingProp.isReverseEverywhereDefined,
        isIdPreserving =
          origProp.isIdPreserving && srcMappingProp.isIdPreserving && dstMappingProp.isIdPreserving)
      edgeBundle(src.entity, dst.entity, inducedProp)
    }
    val embedding = {
      val properties =
        if (srcMappingProp.isFunction && dstMappingProp.isFunction) EdgeBundleProperties.embedding
        else EdgeBundleProperties(isFunction = true, isEverywhereDefined = true)
      edgeBundle(induced.idSet, inputs.edges.idSet, properties)
    }
  }
  def fromJson(j: JsValue) = InducedEdgeBundle((j \ "induceSrc").as[Boolean], (j \ "induceDst").as[Boolean])
}
import InducedEdgeBundle._
case class InducedEdgeBundle(induceSrc: Boolean = true, induceDst: Boolean = true)
    extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input(induceSrc, induceDst)

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output(induceSrc, induceDst)(instance, inputs)
  override def toJson = Json.obj("induceSrc" -> induceSrc, "induceDst" -> induceDst)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val instance = output.instance
    val src = inputs.src.rdd
    val dst = inputs.dst.rdd
    val edges = inputs.edges.rdd

    def getMapping(mappingInput: MagicInputSignature#EdgeBundleTemplate,
                   partitioner: Partitioner): SortedRDD[ID, ID] = {
      val mappingEntity = mappingInput.entity
      val mappingEdges = mappingInput.rdd
      if (mappingEntity.properties.isIdPreserving) {
        // We might save a shuffle in this case.
        mappingEdges.mapValuesWithKeys { case (id, _) => id }.toSortedRDD(partitioner)
      } else {
        mappingEdges
          .map { case (id, edge) => (edge.src, edge.dst) }
          .toSortedRDD(partitioner)
      }
    }

    def joinMapping[V](
      rdd: SortedRDD[ID, V],
      mappingInput: MagicInputSignature#EdgeBundleTemplate): SortedRDD[ID, (V, ID)] = {
      val props = mappingInput.entity.properties
      val mapping = getMapping(mappingInput, rdd.partitioner.get)
      // If the mapping has no duplicates we can use the faster sortedJoin.
      if (props.isFunction) rdd.sortedJoin(mapping)
      // If the mapping can have duplicates we need to use the slower sortedJoinWithDuplicates.
      else rdd.sortedJoinWithDuplicates(mapping)
    }

    val srcInduced = if (!induceSrc) edges else {
      val srcPartitioner = src.partitioner.get
      val byOldSrc = edges
        .map { case (id, edge) => (edge.src, (id, edge)) }
        .toSortedRDD(srcPartitioner)
      val bySrc = joinMapping(byOldSrc, inputs.srcMapping)
        .mapValues { case ((id, edge), newSrc) => (id, Edge(newSrc, edge.dst)) }
      bySrc.values
    }
    val dstInduced = if (!induceDst) srcInduced else {
      val dstPartitioner = dst.partitioner.get
      val byOldDst = srcInduced
        .map { case (id, edge) => (edge.dst, (id, edge)) }
        .toSortedRDD(dstPartitioner)
      val byDst = joinMapping(byOldDst, inputs.dstMapping)
        .mapValues { case ((id, edge), newDst) => (id, Edge(edge.src, newDst)) }
      byDst.values
    }
    val srcIsFunction = !induceSrc || inputs.srcMapping.properties.isFunction
    val dstIsFunction = !induceDst || inputs.dstMapping.properties.isFunction
    if (srcIsFunction && dstIsFunction) {
      val induced = dstInduced.toSortedRDD(edges.partitioner.get)
      output(o.induced, induced)
      output(o.embedding, induced.mapValuesWithKeys { case (id, _) => Edge(id, id) })
    } else {
      // We may end up with way more edges than we had originally. We need a new partitioner.
      val partitioner = rc.partitionerForNRows(dstInduced.count)
      // A non-function mapping can introduce duplicates. We need to generate new IDs.
      val renumbered = dstInduced.randomNumbered(partitioner)
      output(o.induced, renumbered.mapValues { case (oldId, edge) => edge })
      output(o.embedding,
        renumbered.mapValuesWithKeys { case (newId, (oldId, edge)) => Edge(newId, oldId) })
    }
  }
}
