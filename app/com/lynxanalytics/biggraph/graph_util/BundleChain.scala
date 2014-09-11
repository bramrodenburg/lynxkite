package com.lynxanalytics.biggraph.graph_util

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_operations.ConcatenateBundles
import com.lynxanalytics.biggraph.graph_operations.AddConstantAttribute

class BundleChain(bundles: Seq[EdgeBundle],
                  weightsParam: Option[Seq[VertexAttribute[Double]]] = None) {

  assert(bundles.size > 0)
  weightsParam.map { weightsSeq =>
    assert(weightsSeq.size == bundles.size)
    bundles.zip(weightsSeq)
      .foreach { case (bundle, weight) => assert(bundle.asVertexSet == weight.vertexSet) }
  }
  val weights = weightsParam
    .getOrElse(bundles.map(bundle => AddConstantAttribute.run(bundle.asVertexSet, 1.0)))

  assert((0 until (bundles.size - 1))
    .forall(i => bundles(i).dstVertexSet == bundles(i + 1).srcVertexSet))
  val vertexSets = bundles.head.srcVertexSet +: bundles.map(_.dstVertexSet)

  def getCompositeEdgeBundle: (EdgeBundle, VertexAttribute[Double]) = {
    implicit val metaManager = bundles.head.manager
    if (bundles.size == 1) {
      (bundles.head, weights.head)
    } else {
      val splitterIdx = vertexSets
        .zipWithIndex
        .slice(1, vertexSets.size - 1)
        .maxBy { case (vertexSet, idx) => vertexSet.gUID.toString }
        ._2
      val (firstBundle, firstWeights) =
        (new BundleChain(bundles.slice(0, splitterIdx), Some(weights.slice(0, splitterIdx))))
          .getCompositeEdgeBundle
      val (secondBundle, secondWeights) =
        (new BundleChain(bundles.drop(splitterIdx), Some(weights.drop(splitterIdx))))
          .getCompositeEdgeBundle
      import Scripting._
      val op = ConcatenateBundles()
      val res = op(
        op.edgesAB, firstBundle)(
          op.edgesBC, secondBundle)(
            op.weightsAB, firstWeights)(
              op.weightsBC, secondWeights).result
      (res.edgesAC, res.weightsAC)
    }
  }
}
