// Finds all vertices within a given distance from a set of vertices
// using per-vertex neighborhoods as input.
package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import com.lynxanalytics.biggraph.graph_api._
import scala.util.Sorting

object ComputeVertexNeighborhoodFromTriplets extends OpFromJson {
  class Input extends MagicInputSignature {
    val vertices = vertexSet
    val edges = edgeBundle(vertices, vertices)
    // The list of outgoing edges.
    val srcTripletMapping = vertexAttribute[Array[ID]](vertices)
    // The list of incoming edges.
    val dstTripletMapping = vertexAttribute[Array[ID]](vertices)
  }
  class Output(implicit instance: MetaGraphOperationInstance) extends MagicOutput(instance) {
    val neighborhood = scalar[Set[ID]]
  }
  def fromJson(j: JsValue) =
    ComputeVertexNeighborhoodFromTriplets((j \ "centers").as[Seq[ID]], (j \ "radius").as[Int], (j \ "maxCount").as[Int])
}
import ComputeVertexNeighborhoodFromTriplets._
case class ComputeVertexNeighborhoodFromTriplets(
    centers: Seq[ID],
    radius: Int,
    maxCount: Int) extends TypedMetaGraphOp[Input, Output] {

  @transient override lazy val inputs = new Input

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance)
  override def toJson = Json.obj("centers" -> centers, "radius" -> radius, "maxCount" -> maxCount)

  def execute(inputDatas: DataSet, o: Output, output: OutputBuilder, rc: RuntimeContext) = {
    implicit val id = inputDatas
    val edges = inputs.edges.rdd
    val all = inputs.srcTripletMapping.rdd.fullOuterJoin(inputs.dstTripletMapping.rdd)
    var neighborhood = centers.toArray
    for (i <- 0 until radius) {
      Sorting.quickSort(neighborhood)
      val neighborEdges = all
        .restrictToIdSet(neighborhood)
        .flatMap { case (id, (srcEdge, dstEdge)) => (srcEdge ++ dstEdge).flatten }
        .distinct.collect
      Sorting.quickSort(neighborEdges)
      neighborhood = edges.restrictToIdSet(neighborEdges)
        .flatMap { case (id, edge) => Iterator(edge.src, edge.dst) }
        .distinct
        .take(maxCount)
    }
    // Isolated points are lost in the above loop. Add back centers to make sure they are present.
    val nonCenters = neighborhood.toSet -- centers
    val trimmed = nonCenters.take(maxCount - centers.size)
    output(o.neighborhood, trimmed ++ centers)
  }
}
