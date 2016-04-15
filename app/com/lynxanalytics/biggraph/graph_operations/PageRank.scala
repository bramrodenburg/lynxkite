// PageRank is an estimate of the probability that starting from a random vertex
// a random walk will take us to a specific vertex.
package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.HybridRDD
import com.lynxanalytics.biggraph.spark_util.Implicits._

object PageRank extends OpFromJson {
  class Input extends MagicInputSignature {
    val (vs, es) = graph
    val weights = edgeAttribute[Double](es)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val pagerank = vertexAttribute[Double](inputs.vs.entity)
  }
  def fromJson(j: JsValue) = PageRank(
    (j \ "dampingFactor").as[Double],
    (j \ "iterations").as[Int])
}
import PageRank._
case class PageRank(dampingFactor: Double,
                    iterations: Int)
    extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj("dampingFactor" -> dampingFactor, "iterations" -> iterations)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val edges = inputs.es.rdd
    // We only keep positive weights. Otherwise per-node normalization may not make sense.
    val weights = inputs.weights.rdd.filter(_._2 > 0.0)
    val vertices = inputs.vs.rdd
    val vertexPartitioner = vertices.partitioner.get
    val edgePartitioner = edges.partitioner.get

    val edgesWithWeights = edges.sortedJoin(weights).map {
      case (_, (edge, weight)) => edge.src -> (edge.dst, weight)
    }
    edgesWithWeights.persist(spark.storage.StorageLevel.DISK_ONLY)
    // The sum of weights for every src vertex.
    val sumWeights = edgesWithWeights
      .mapValues(_._2) // Discard dst and keep weights.
      .reduceBySortedKey(edgePartitioner, _ + _)

    // Join the sum of weights per src to the src vertices in the edge RDD.
    val edgesWithSumWeights = HybridRDD(edgesWithWeights).lookupAndCoalesce(sumWeights)
    // Normalize the weights for every src vertex.
    val targetsWithWeights = HybridRDD(
      edgesWithSumWeights
        .mapValues { case ((dst, weight), sumWeight) => (dst, weight / sumWeight) })
    targetsWithWeights.persist(spark.storage.StorageLevel.DISK_ONLY)

    var pageRank = vertices.mapValues(_ => 1.0).sortedRepartition(edgePartitioner)
    val vertexCount = vertices.count

    for (i <- 0 until iterations) {
      // No need for repartitioning since we reduce anyway.
      val incomingRank = targetsWithWeights.lookupAndCoalesce(pageRank)
        .map {
          case (src, ((dst, weight), pr)) => dst -> pr * weight * dampingFactor
        }
        .reduceBySortedKey(edgePartitioner, _ + _)

      val totalIncoming = incomingRank.values.sum
      val distributedExtraWeight = (vertexCount - totalIncoming) / vertexCount

      pageRank = pageRank.sortedLeftOuterJoin(incomingRank)
        .mapValues {
          case (oldRank, incoming) => distributedExtraWeight + incoming.getOrElse(0.0)
        }
    }
    output(o.pagerank, pageRank.sortedRepartition(vertexPartitioner))
  }
}
