package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.BigGraphEnvironment
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.MetaGraphManager.StringAsUUID
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_operations.DynamicValue
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.serving.{ FlyingResult, User }
import com.lynxanalytics.biggraph.spark_util

import scala.collection.mutable

case class VertexDiagramSpec(
  val vertexSetId: String,
  val filters: Seq[FEVertexAttributeFilter],
  val mode: String, // For now, one of "bucketed", "sampled".

  // ** Parameters for bucketed view **
  // Empty string means no bucketing on that axis.
  val xBucketingAttributeId: String = "",
  val xNumBuckets: Int = 1,
  val xAxisOptions: AxisOptions = AxisOptions(),
  val yBucketingAttributeId: String = "",
  val yNumBuckets: Int = 1,
  val yAxisOptions: AxisOptions = AxisOptions(),

  // ** Parameters for sampled view **
  val centralVertexIds: Seq[String] = Seq(),
  // Edge bundle used to find neighborhood of the central vertex.
  val sampleSmearEdgeBundleId: String = "",
  val attrs: Seq[String] = Seq(),
  val radius: Int = 1)

case class FEVertex(
  // For bucketed view:
  size: Double = 0.0,
  x: Int = 0,
  y: Int = 0,

  // For sampled view:
  id: String = "",
  attrs: Map[String, DynamicValue] = Map())

case class VertexDiagramResponse(
  val diagramId: String,
  val vertices: Seq[FEVertex],
  val mode: String, // as specified in the request

  // ** Only set for bucketed view **
  val xLabelType: String = "",
  val yLabelType: String = "",
  val xLabels: Seq[String] = Seq(),
  val yLabels: Seq[String] = Seq(),
  val xFilters: Seq[String] = Seq(),
  val yFilters: Seq[String] = Seq())

case class AggregatedAttribute(
  // The GUID of the attribute.
  attributeId: String,
  // The aggregation we want to apply on it.
  aggregator: String)

case class EdgeDiagramSpec(
  // In the context of an FEGraphRequest "idx[4]" means the diagram requested by vertexSets(4).
  // Otherwise a UUID obtained by a previous vertex diagram request.
  srcDiagramId: String,
  dstDiagramId: String,
  // These are copied verbatim to the response, used by the FE to identify EdgeDiagrams.
  srcIdx: Int,
  dstIdx: Int,
  // The GUID of the edge bundle to plot.
  edgeBundleId: String,
  // Specification of filters that should be applied to attributes of the edge bundle.
  filters: Seq[FEVertexAttributeFilter],
  // If not set, we use constant 1 as weight.
  edgeWeightId: String = "",
  // Whether to generate 3D coordinates for the vertices.
  layout3D: Boolean,
  // Attributes to be returned together with the edges. As one visualized edge can correspond to
  // many actual edges, clients always have to specify an aggregator as well. For now, this only
  // works for small edge set visualizations (i.e. sampled mode).
  attrs: Seq[AggregatedAttribute] = Seq())

case class BundleSequenceStep(bundle: String, reversed: Boolean)

case class FEEdge(
  // idx of source vertex in the vertices Seq in the corresponding VertexDiagramResponse.
  a: Int,
  // idx of destination vertex in the vertices Seq in the corresponding VertexDiagramResponse.
  b: Int,
  size: Double,
  // Keys are composed as attributeId:aggregator.
  attrs: Map[String, DynamicValue] = Map())

case class FE3DPosition(x: Double, y: Double, z: Double)

case class EdgeDiagramResponse(
  srcDiagramId: String,
  dstDiagramId: String,

  // Copied from the request.
  srcIdx: Int,
  dstIdx: Int,

  edges: Seq[FEEdge],

  // The vertex coordinates, if "layout3D" was true in the request.
  layout3D: Map[String, FE3DPosition])

case class FEGraphRequest(
  vertexSets: Seq[VertexDiagramSpec],
  edgeBundles: Seq[EdgeDiagramSpec])

case class FEGraphResponse(
  vertexSets: Seq[VertexDiagramResponse],
  edgeBundles: Seq[EdgeDiagramResponse])

case class AxisOptions(
  logarithmic: Boolean = false)

case class HistogramSpec(
  attributeId: String,
  vertexFilters: Seq[FEVertexAttributeFilter],
  numBuckets: Int,
  axisOptions: AxisOptions,
  // Set only if we ask for an edge attribute histogram and provided vertexFilters should be
  // applied on the end-vertices of edges.
  edgeBundleId: String = "",
  edgeFilters: Seq[FEVertexAttributeFilter] = Seq())

case class HistogramResponse(
    labelType: String,
    labels: Seq[String],
    sizes: Seq[Long]) {
  val validLabelTypes = Seq("between", "bucket")
  assert(validLabelTypes.contains(labelType),
    s"$labelType is not a valid label type. They are: $validLabelTypes")
}

case class ScalarValueRequest(
  val scalarId: String,
  val calculate: Boolean)

case class CenterRequest(
  vertexSetId: String,
  count: Int,
  filters: Seq[FEVertexAttributeFilter])

case class CenterResponse(
  val centers: Seq[String])

class GraphDrawingController(env: BigGraphEnvironment) {
  implicit val metaManager = env.metaGraphManager
  implicit val dataManager = env.dataManager

  def getVertexDiagram(user: User, request: VertexDiagramSpec): VertexDiagramResponse = {
    request.mode match {
      case "bucketed" => getBucketedVertexDiagram(request)
      case "sampled" => getSampledVertexDiagram(request)
    }
  }

  def getSampledVertexDiagram(request: VertexDiagramSpec): VertexDiagramResponse = {
    val vertexSet = metaManager.vertexSet(request.vertexSetId.asUUID)
    dataManager.cache(vertexSet)

    val iaaop = graph_operations.IdAsAttribute()
    val idAttr = iaaop(iaaop.vertices, vertexSet).result.vertexIds
    loadGUIDsToMemory(request.filters.map(_.attributeId))
    val filtered = FEFilters.filter(vertexSet, request.filters)
    loadGUIDsToMemory(request.attrs)

    val centers = if (request.centralVertexIds == Seq("*")) {
      // Try to show the whole graph.
      val op = graph_operations.SampleVertices(10000)
      op(op.vs, filtered).result.sample.value
    } else {
      request.centralVertexIds.map(_.toLong)
    }

    val idSet = if (request.radius > 0) {
      val smearBundle = metaManager.edgeBundle(request.sampleSmearEdgeBundleId.asUUID)
      dataManager.cache(smearBundle)
      val triplets = tripletMapping(smearBundle, sampled = false)
      val nop = graph_operations.ComputeVertexNeighborhoodFromTriplets(centers, request.radius)
      val nopres = nop(
        nop.vertices, vertexSet)(
          nop.edges, smearBundle)(
            nop.srcTripletMapping, triplets.srcEdges)(
              nop.dstTripletMapping, triplets.dstEdges).result
      nopres.neighborhood.value
    } else {
      centers.toSet
    }

    val diagramMeta = {
      val op = graph_operations.SampledView(idSet)
      op(op.vertices, vertexSet)(op.ids, idAttr)(op.filtered, filtered).result.svVertices
    }
    val vertices = diagramMeta.value

    val attrs = request.attrs.map { attrId =>
      attrId -> {
        val attr = metaManager.vertexAttribute(attrId.asUUID)
        val dyn = graph_operations.VertexAttributeToDynamicValue.run(attr)
        val op = graph_operations.CollectAttribute[DynamicValue](idSet)
        op(op.attr, dyn).result.idToAttr.value
      }
    }.toMap

    VertexDiagramResponse(
      diagramId = diagramMeta.gUID.toString,
      vertices = vertices.map(v =>
        FEVertex(
          id = v.toString,
          attrs = attrs.mapValues(_.getOrElse(v, DynamicValue(defined = false))))),
      mode = "sampled")
  }

  def getDiagramFromBucketedAttributes[S, T](
    original: VertexSet,
    filtered: VertexSet,
    xBucketedAttr: graph_operations.BucketedAttribute[S],
    yBucketedAttr: graph_operations.BucketedAttribute[T]): Scalar[spark_util.IDBuckets[(Int, Int)]] = {

    val cop = graph_operations.CountVertices()
    val originalCount = cop(cop.vertices, original).result.count
    val op = graph_operations.VertexBucketGrid(xBucketedAttr.bucketer, yBucketedAttr.bucketer)
    var builder = op(op.filtered, filtered)(op.vertices, original)(op.originalCount, originalCount)
    if (xBucketedAttr.nonEmpty) {
      builder = builder(op.xAttribute, xBucketedAttr.attribute)
    }
    if (yBucketedAttr.nonEmpty) {
      builder = builder(op.yAttribute, yBucketedAttr.attribute)
    }
    builder.result.buckets
  }

  def getBucketedVertexDiagram(request: VertexDiagramSpec): VertexDiagramResponse = {
    val vertexSet = metaManager.vertexSet(request.vertexSetId.asUUID)
    dataManager.cache(vertexSet)
    loadGUIDsToMemory(request.filters.map(_.attributeId))
    val filtered = FEFilters.filter(vertexSet, request.filters)

    val xBucketedAttr = if (request.xBucketingAttributeId.nonEmpty) {
      val attribute = metaManager.vertexAttribute(request.xBucketingAttributeId.asUUID)
      dataManager.cache(attribute)
      FEBucketers.bucketedAttribute(
        metaManager, dataManager, attribute, request.xNumBuckets, request.xAxisOptions)
    } else {
      graph_operations.BucketedAttribute.emptyBucketedAttribute
    }
    val yBucketedAttr = if (request.yBucketingAttributeId.nonEmpty) {
      val attribute = metaManager.vertexAttribute(request.yBucketingAttributeId.asUUID)
      dataManager.cache(attribute)
      FEBucketers.bucketedAttribute(
        metaManager, dataManager, attribute, request.yNumBuckets, request.yAxisOptions)
    } else {
      graph_operations.BucketedAttribute.emptyBucketedAttribute
    }

    val diagramMeta = getDiagramFromBucketedAttributes(
      vertexSet, filtered, xBucketedAttr, yBucketedAttr)
    val diagram = dataManager.get(diagramMeta).value

    val xBucketer = xBucketedAttr.bucketer
    val yBucketer = yBucketedAttr.bucketer
    val vertices = for (x <- (0 until xBucketer.numBuckets); y <- (0 until yBucketer.numBuckets))
      yield FEVertex(x = x, y = y, size = diagram.counts((x, y)))

    VertexDiagramResponse(
      diagramId = diagramMeta.gUID.toString,
      vertices = vertices,
      mode = "bucketed",
      xLabelType = xBucketer.labelType,
      yLabelType = yBucketer.labelType,
      xLabels = xBucketer.bucketLabels,
      yLabels = yBucketer.bucketLabels,
      xFilters = xBucketer.bucketFilters,
      yFilters = yBucketer.bucketFilters)
  }

  private def loadGUIDsToMemory(gUIDs: Seq[String]): Unit = {
    gUIDs.foreach(id => dataManager.cache(metaManager.entity(id.asUUID)))
  }

  private def tripletMapping(
    eb: EdgeBundle, sampled: Boolean): graph_operations.TripletMapping.Output = {
    val op =
      if (sampled) graph_operations.TripletMapping(sampleSize = 500000)
      else graph_operations.TripletMapping()
    val res = op(op.edges, eb).result
    dataManager.cache(res.srcEdges)
    dataManager.cache(res.dstEdges)
    return res
  }

  private def mappedAttribute[T](mapping: Attribute[Array[ID]],
                                 attr: Attribute[T],
                                 target: EdgeBundle): Attribute[T] = {
    val op = new graph_operations.VertexToEdgeAttribute[T]()
    val res = op(op.mapping, mapping)(op.original, attr)(op.target, target).result.mappedAttribute
    dataManager.cache(res)
    res
  }

  private def mappedFilter[T](
    mapping: Attribute[Array[ID]],
    fa: graph_operations.FilteredAttribute[T],
    target: EdgeBundle): graph_operations.FilteredAttribute[T] = {
    val mattr = mappedAttribute(mapping, fa.attribute, target)
    graph_operations.FilteredAttribute(mattr, fa.filter)
  }

  def indexFromBucketedAttribute[T](
    base: Attribute[Int],
    ba: graph_operations.BucketedAttribute[T]): Attribute[Int] = {

    val iop = graph_operations.Indexer(ba.bucketer)
    iop(iop.baseIndices, base)(iop.bucketAttribute, ba.attribute).result.indices
  }

  def indexFromIndexingSeq(
    filtered: VertexSet,
    seq: Seq[graph_operations.BucketedAttribute[_]]): Attribute[Int] = {

    val startingBase: Attribute[Int] = graph_operations.AddConstantAttribute.run(filtered, 0)
    seq.foldLeft(startingBase) { case (b, ba) => indexFromBucketedAttribute(b, ba) }
  }

  def edgeIndexFromBucketedAttribute[T](
    original: EdgeBundle,
    base: Attribute[Int],
    tripletMapping: Attribute[Array[ID]],
    ba: graph_operations.BucketedAttribute[T]): Attribute[Int] = {

    val mattr = mappedAttribute(tripletMapping, ba.attribute, original)

    val iop = graph_operations.Indexer(ba.bucketer)
    iop(iop.baseIndices, base)(iop.bucketAttribute, mattr).result.indices
  }

  def edgeIndexFromIndexingSeq(
    original: EdgeBundle,
    filteredIds: VertexSet,
    tripletMapping: Attribute[Array[ID]],
    seq: Seq[graph_operations.BucketedAttribute[_]]): Attribute[Int] = {

    val startingBase: Attribute[Int] =
      graph_operations.AddConstantAttribute.run(filteredIds, 0)
    seq.foldLeft(startingBase) {
      case (b, ba) => edgeIndexFromBucketedAttribute(original, b, tripletMapping, ba)
    }
  }

  def getSmallEdgeSet(
    eb: EdgeBundle,
    srcView: graph_operations.VertexView,
    dstView: graph_operations.VertexView): Option[Seq[(ID, Edge)]] = {

    val tm = tripletMapping(eb, sampled = false)
    if (srcView.vertexIndices.isDefined) {
      val vertexIds = srcView.vertexIndices.get.keySet
      val op = graph_operations.EdgesForVertices(vertexIds, 50000, bySource = true)
      val edges =
        op(op.edges, eb)(op.tripletMapping, tm.srcEdges).result.edges.value
      if (edges.isDefined) return edges
    }
    if (dstView.vertexIndices.isDefined) {
      val vertexIds = dstView.vertexIndices.get.keySet
      val op = graph_operations.EdgesForVertices(vertexIds, 50000, bySource = false)
      val edges =
        op(op.edges, eb)(op.tripletMapping, tm.dstEdges).result.edges.value
      if (edges.isDefined) return edges
    }
    return None
  }

  def getAggregatedAttributeByCoord[From, To](
    ids: Set[ID],
    attributeWithAggregator: AttributeWithLocalAggregator[From, To],
    idToCoordMapping: Map[ID, (Int, Int)]): Map[(Int, Int), DynamicValue] = {

    val attrMap = graph_operations.RestrictAttributeToIds.run(
      attributeWithAggregator.attr, ids).value.toMap
    val byCoordMap = attributeWithAggregator.aggregator.aggregateByKey(
      attrMap.toSeq.map { case (id, value) => idToCoordMapping(id) -> value })
    implicit val ttT = attributeWithAggregator.aggregator.outputTypeTag(
      attributeWithAggregator.attr.typeTag)
    byCoordMap.mapValues(DynamicValue.convert[To](_))
  }

  def getEdgeDiagram(user: User, request: EdgeDiagramSpec): EdgeDiagramResponse = {
    val srcView = graph_operations.VertexView.fromDiagram(
      metaManager.scalar(request.srcDiagramId.asUUID))
    val dstView = graph_operations.VertexView.fromDiagram(
      metaManager.scalar(request.dstDiagramId.asUUID))
    val edgeBundle = metaManager.edgeBundle(request.edgeBundleId.asUUID)
    dataManager.cache(edgeBundle)
    val weights = if (request.edgeWeightId.isEmpty) {
      graph_operations.AddConstantAttribute.run(edgeBundle.asVertexSet, 1.0)
    } else {
      val w = metaManager.vertexAttributeOf[Double](request.edgeWeightId.asUUID)
      dataManager.cache(w)
      w
    }
    assert(
      weights.vertexSet == edgeBundle.asVertexSet,
      "The requested edge weight attribute does not belong to the requested edge bundle.\n" +
        "Edge bundle: $edgeBundle\nWeight attribute: $weights")
    assert(srcView.vertexSet.gUID == edgeBundle.srcVertexSet.gUID,
      "Source vertex set does not match edge bundle source." +
        s"\nSource: ${srcView.vertexSet}\nEdge bundle source: ${edgeBundle.srcVertexSet}")
    assert(dstView.vertexSet.gUID == edgeBundle.dstVertexSet.gUID,
      "Destination vertex set does not match edge bundle destination." +
        s"\nSource: ${dstView.vertexSet}\nEdge bundle destination: ${edgeBundle.dstVertexSet}")

    val smallEdgeSetOption = getSmallEdgeSet(edgeBundle, srcView, dstView)
    val feEdges = smallEdgeSetOption match {
      case Some(smallEdgeSet) => {
        log.info("PERF Small edge set mode for request: " + request)
        val smallEdgeSetMap = smallEdgeSet.toMap
        val filteredEdgeSetIDs = FEFilters.localFilter(smallEdgeSetMap.keySet, request.filters)
        val filteredEdgeSet = filteredEdgeSetIDs.map(id => id -> smallEdgeSetMap(id))
        val srcIdxMapping = srcView.vertexIndices.getOrElse {
          val indexAttr = indexFromIndexingSeq(srcView.vertexSet, srcView.indexingSeq)
          val srcVertexIds = filteredEdgeSet.map { case (id, edge) => edge.src }.toSet
          graph_operations.RestrictAttributeToIds.run(indexAttr, srcVertexIds).value
        }
        val dstIdxMapping = dstView.vertexIndices.getOrElse {
          val indexAttr = indexFromIndexingSeq(dstView.vertexSet, dstView.indexingSeq)
          val dstVertexIds = filteredEdgeSet.map { case (id, edge) => edge.dst }.toSet
          graph_operations.RestrictAttributeToIds.run(indexAttr, dstVertexIds).value
        }
        val idToCoordMapping =
          filteredEdgeSet.flatMap {
            case (id, edge) =>
              val src = edge.src
              val dst = edge.dst
              if (srcIdxMapping.contains(src) && dstIdxMapping.contains(dst)) {
                Some(id -> (srcIdxMapping(src), dstIdxMapping(dst)))
              } else {
                None
              }
          }.toMap
        val attributesWithAggregators: Map[String, AttributeWithLocalAggregator[_, _]] =
          request.attrs.map(
            attr => (attr.attributeId + ":" + attr.aggregator) -> AttributeWithLocalAggregator(
              metaManager.vertexAttribute(attr.attributeId.asUUID),
              attr.aggregator)).toMap
        val attributeValues = attributesWithAggregators.mapValues(
          getAggregatedAttributeByCoord(filteredEdgeSetIDs, _, idToCoordMapping))
        val weightMap = graph_operations.RestrictAttributeToIds.run(
          weights, filteredEdgeSetIDs).value.toMap
        val counts = mutable.Map[(Int, Int), Double]().withDefaultValue(0.0)
        idToCoordMapping.foreach {
          case (id, coord) =>
            counts(coord) += weightMap(id)
        }
        counts
          .toMap
          .map {
            case ((s, d), c) =>
              FEEdge(
                s, d, c,
                attrs = attributeValues.mapValues(
                  _.getOrElse((s, d), DynamicValue(defined = false))))
          }
          .toSeq
      }
      case None => {
        log.info("PERF Huge edge set mode for request: " + request)
        val edgeFilters = request.filters.map(_.toFilteredAttribute)
        val filtered = getFilteredEdgeIds(edgeBundle, srcView.filters, dstView.filters, edgeFilters)

        val srcIndices = edgeIndexFromIndexingSeq(
          edgeBundle, filtered.ids, filtered.srcTripletMapping, srcView.indexingSeq)
        val dstIndices = edgeIndexFromIndexingSeq(
          edgeBundle, filtered.ids, filtered.dstTripletMapping, dstView.indexingSeq)

        val cop = graph_operations.CountEdges()
        val originalEdgeCount = cop(cop.edges, edgeBundle).result.count
        val countOp = graph_operations.IndexPairCounter()
        val counts = countOp(
          countOp.xIndices, srcIndices)(
            countOp.yIndices, dstIndices)(
              countOp.original, edgeBundle.asVertexSet)(
                countOp.weights, weights)(
                  countOp.originalCount, originalEdgeCount).result.counts.value
        counts.map { case ((s, d), c) => FEEdge(s, d, c) }.toSeq
      }
    }
    log.info("PERF edge counts computed")
    EdgeDiagramResponse(
      request.srcDiagramId,
      request.dstDiagramId,
      request.srcIdx,
      request.dstIdx,
      feEdges,
      if (request.layout3D) ForceLayout3D(feEdges) else Map())
  }

  def getComplexView(user: User, request: FEGraphRequest): FEGraphResponse = {
    val vertexDiagrams = request.vertexSets.map(getVertexDiagram(user, _))
    val idxPattern = "idx\\[(\\d+)\\]".r
    def resolveDiagramId(reference: String): String = {
      reference match {
        case idxPattern(idx) => vertexDiagrams(idx.toInt).diagramId
        case id: String => id
      }
    }
    val modifiedEdgeSpecs = request.edgeBundles
      .map(eb => eb.copy(
        srcDiagramId = resolveDiagramId(eb.srcDiagramId),
        dstDiagramId = resolveDiagramId(eb.dstDiagramId)))
    val edgeDiagrams = modifiedEdgeSpecs.map(getEdgeDiagram(user, _))
    spark_util.Counters.printAll
    return FEGraphResponse(vertexDiagrams, edgeDiagrams)
  }

  private def getFilteredVSByFA(
    vertexSet: VertexSet,
    vertexFilters: Seq[graph_operations.FilteredAttribute[_]]): VertexSet = {
    loadGUIDsToMemory(vertexFilters.map(_.attribute.gUID.toString))
    FEFilters.filterFA(vertexSet, vertexFilters)
  }

  private def getFilteredVS(
    vertexSet: VertexSet,
    vertexFilters: Seq[FEVertexAttributeFilter]): VertexSet = {
    loadGUIDsToMemory(vertexFilters.map(_.attributeId))
    FEFilters.filter(vertexSet, vertexFilters)
  }

  private case class FilteredEdges(
    ids: VertexSet,
    srcTripletMapping: Attribute[Array[ID]],
    dstTripletMapping: Attribute[Array[ID]])

  private def getFilteredEdgeIds(
    edgeBundle: EdgeBundle,
    srcFilters: Seq[graph_operations.FilteredAttribute[_]],
    dstFilters: Seq[graph_operations.FilteredAttribute[_]],
    edgeFilters: Seq[graph_operations.FilteredAttribute[_]]): FilteredEdges = {
    val sampledTrips = tripletMapping(edgeBundle, sampled = true)
    val sampledEdges = getFilteredEdgeIds(sampledTrips, edgeBundle, srcFilters, dstFilters, edgeFilters)
    // TODO: See if we can eliminate the extra stage from this "count".
    val count = {
      val op = graph_operations.CountVertices()
      op(op.vertices, sampledEdges.ids).result.count.value
    }
    if (count >= 50000) {
      sampledEdges
    } else {
      val fullTrips = tripletMapping(edgeBundle, sampled = false)
      getFilteredEdgeIds(fullTrips, edgeBundle, srcFilters, dstFilters, edgeFilters)
    }
  }

  private def getFilteredEdgeIds(
    trips: graph_operations.TripletMapping.Output,
    edgeBundle: EdgeBundle,
    srcFilters: Seq[graph_operations.FilteredAttribute[_]],
    dstFilters: Seq[graph_operations.FilteredAttribute[_]],
    edgeFilters: Seq[graph_operations.FilteredAttribute[_]]): FilteredEdges = {
    val srcMapped = srcFilters.map(mappedFilter(trips.srcEdges, _, edgeBundle))
    val dstMapped = dstFilters.map(mappedFilter(trips.dstEdges, _, edgeBundle))
    val ids = getFilteredVSByFA(edgeBundle.asVertexSet, srcMapped ++ dstMapped ++ edgeFilters)
    FilteredEdges(ids, trips.srcEdges, trips.dstEdges)
  }

  def getCenter(user: User, request: CenterRequest): CenterResponse = {
    val vertexSet = metaManager.vertexSet(request.vertexSetId.asUUID)
    dataManager.cache(vertexSet)
    loadGUIDsToMemory(request.filters.map(_.attributeId))
    val filtered = FEFilters.filter(vertexSet, request.filters)
    val sampled = {
      val op = graph_operations.SampleVertices(request.count)
      op(op.vs, filtered).result.sample.value
    }
    CenterResponse(sampled.map(_.toString))
  }

  def getHistogram(user: User, request: HistogramSpec): HistogramResponse = {
    val vertexAttribute = metaManager.vertexAttribute(request.attributeId.asUUID)
    dataManager.cache(vertexAttribute.vertexSet)
    dataManager.cache(vertexAttribute)
    loadGUIDsToMemory(request.vertexFilters.map(_.attributeId))
    val bucketedAttr = FEBucketers.bucketedAttribute(
      metaManager, dataManager, vertexAttribute, request.numBuckets, request.axisOptions)
    val filteredVS = if (request.edgeBundleId.isEmpty) {
      getFilteredVS(vertexAttribute.vertexSet, request.vertexFilters)
    } else {
      val edgeBundle = metaManager.edgeBundle(request.edgeBundleId.asUUID)
      val vertexFilters = request.vertexFilters.map(_.toFilteredAttribute)
      val edgeFilters = request.edgeFilters.map(_.toFilteredAttribute)
      getFilteredEdgeIds(edgeBundle, vertexFilters, vertexFilters, edgeFilters).ids
    }
    val histogram = bucketedAttr.toHistogram(filteredVS)
    val counts = histogram.counts.value
    spark_util.Counters.printAll
    HistogramResponse(
      bucketedAttr.bucketer.labelType,
      bucketedAttr.bucketer.bucketLabels,
      (0 until bucketedAttr.bucketer.numBuckets).map(counts.getOrElse(_, 0L)))
  }

  def getScalarValue(user: User, request: ScalarValueRequest): DynamicValue = {
    val scalar = metaManager.scalar(request.scalarId.asUUID)
    if (!request.calculate && !dataManager.isCalculated(scalar)) {
      throw new FlyingResult(play.api.mvc.Results.NotFound("Value is not calculated yet"))
    }
    dynamicValue(scalar)
  }

  private def dynamicValue[T](scalar: Scalar[T]) = {
    implicit val tt = scalar.typeTag
    graph_operations.DynamicValue.convert(scalar.value)
  }
}
