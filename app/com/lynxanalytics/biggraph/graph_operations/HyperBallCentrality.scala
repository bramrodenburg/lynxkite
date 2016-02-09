// Estimates Centrality for each vertex using the HyperBall algorithm.
// http://vigna.di.unimi.it/ftp/papers/HyperBall.pdf
// HyperBall uses HyperLogLog counters to estimate sizes of large sets, so
// the centrality values calculated here are approximations. Note that this
// algorithm does not take weights or parallel edges into account.
package com.lynxanalytics.biggraph.graph_operations

import scala.annotation.tailrec

import org.apache.spark._

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util.SortedRDD
import com.lynxanalytics.biggraph.spark_util.UniqueSortedRDD

import com.twitter.algebird.HyperLogLogMonoid
import com.twitter.algebird.HLL
import com.twitter.algebird.HyperLogLog._

object HyperBallCentrality extends OpFromJson {
  private val algorithmParameter = NewParameter("algorithm", "Harmonic")
  private val bitsParameter = NewParameter("bits", 8)

  class Input extends MagicInputSignature {
    val (vs, es) = graph
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val centrality = vertexAttribute[Double](inputs.vs.entity)
  }
  def fromJson(j: JsValue) = HyperBallCentrality(
    (j \ "maxDiameter").as[Int],
    algorithmParameter.fromJson(j),
    bitsParameter.fromJson(j))
}
import HyperBallCentrality._
case class HyperBallCentrality(maxDiameter: Int, algorithm: String, bits: Int)
    extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj("maxDiameter" -> maxDiameter) ++
    algorithmParameter.toJson(algorithm) ++
    bitsParameter.toJson(bits)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val vertices = inputs.vs.rdd
    val vertexPartitioner = vertices.partitioner.get
    val edges = inputs.es.rdd.map { case (id, edge) => (edge.src, edge.dst) }
      .groupBySortedKey(vertexPartitioner).cache()
    // Hll counters are used to estimate set sizes.
    val globalHll = new HyperLogLogMonoid(bits)
    val hyperBallCounters = vertices.mapValuesWithKeys {
      // Initialize a counter for every vertex
      case (vid, _) => globalHll(vid)
    }
    // We have to keep track of the HyperBall sizes for the actual
    // and the previous diameter.
    val hyperBallSizes = vertices.mapValues { _ => (1, 1) }

    val centralities = algorithm match {
      case "Harmonic" =>
        getHarmonicCentralities(
          diameter = 1,
          harmonicCentralities = vertices.mapValues { _ => 0.0 },
          hyperBallCounters = hyperBallCounters,
          hyperBallSizes = hyperBallSizes,
          vertexPartitioner,
          edges)

      case "Lin" =>
        val (finalSumDistances, sizes) = getMeasures(
          diameter = 1,
          sumDistances = vertices.mapValues { _ => 0 },
          hyperBallCounters = hyperBallCounters,
          hyperBallSizes = hyperBallSizes,
          vertexPartitioner,
          edges)
        finalSumDistances.sortedJoin(sizes).mapValuesWithKeys {
          case (vid, (sumDistance, size)) => {
            if (sumDistance == 0) {
              1.0 // Compute 1.0 for vertices with empty coreachable set by definition.
            } else {
              size.toDouble * size.toDouble / sumDistance.toDouble
            }
          }
        }

      case "Average distance" =>
        val (finalSumDistances, sizes) = getMeasures(
          diameter = 1,
          sumDistances = vertices.mapValues { _ => 0 },
          hyperBallCounters = hyperBallCounters,
          hyperBallSizes = hyperBallSizes,
          vertexPartitioner,
          edges)
        finalSumDistances.sortedJoin(sizes).mapValuesWithKeys {
          case (vid, (sumDistance, size)) => {
            val others = size - 1 // size includes the vertex itself
            if (others == 0) 0.0
            else sumDistance.toDouble / others.toDouble
          }
        }
    }

    output(o.centrality, centralities)
  }

  /* For every vertex A returns the sum of the distances to A and
     the size of the coreachable set of A.*/
  @tailrec private def getMeasures(
    diameter: Int, // Max diameter - iterations - to check
    sumDistances: UniqueSortedRDD[ID, Int], // The sum of the distances to every vertex
    hyperBallCounters: UniqueSortedRDD[ID, HLL], // HLLs counting the coreachable sets
    hyperBallSizes: UniqueSortedRDD[ID, (Int, Int)], // Sizes of the coreachable sets
    vertexPartitioner: Partitioner,
    edges: UniqueSortedRDD[ID, Iterable[ID]]): (UniqueSortedRDD[ID, Int], UniqueSortedRDD[ID, Int]) = {

    val newHyperBallCounters = getNextHyperBalls(
      hyperBallCounters, vertexPartitioner, edges).cache()
    val newHyperBallSizes = hyperBallSizes.sortedJoin(newHyperBallCounters).mapValues {
      case ((_, newValue), hll) =>
        (newValue, hll.estimatedSize.toInt)
    }
    val newSumDistances = sumDistances
      .sortedJoin(newHyperBallSizes)
      .mapValues {
        case (original, (oldSize, newSize)) => {
          original + ((newSize - oldSize) * diameter)
        }
      }

    if (diameter < maxDiameter) {
      getMeasures(diameter + 1, newSumDistances,
        newHyperBallCounters, newHyperBallSizes, vertexPartitioner, edges)
    } else {
      (newSumDistances, newHyperBallSizes.mapValuesWithKeys { case (_, (_, newSize)) => newSize })
    }
  }

  /* Returns the harmonic centrality of every vertex.*/
  @tailrec private def getHarmonicCentralities(
    diameter: Int, // Max diameter - iterations - to check
    harmonicCentralities: UniqueSortedRDD[ID, Double],
    hyperBallCounters: UniqueSortedRDD[ID, HLL], // HLLs counting the coreachable sets
    hyperBallSizes: UniqueSortedRDD[ID, (Int, Int)], // Sizes of the coreachable sets
    vertexPartitioner: Partitioner,
    edges: UniqueSortedRDD[ID, Iterable[ID]]): UniqueSortedRDD[ID, Double] = {

    val newHyperBallCounters = getNextHyperBalls(
      hyperBallCounters, vertexPartitioner, edges).cache()
    val newHyperBallSizes = hyperBallSizes.sortedJoin(newHyperBallCounters).mapValues {
      case ((_, newValue), hll) =>
        (newValue, hll.estimatedSize.toInt)
    }
    val newHarmonicCentralities = harmonicCentralities
      .sortedJoin(newHyperBallSizes)
      .mapValues {
        case (original, (oldSize, newSize)) => {
          original + ((newSize - oldSize).toDouble / diameter)
        }
      }

    if (diameter < maxDiameter) {
      getHarmonicCentralities(diameter + 1, newHarmonicCentralities,
        newHyperBallCounters, newHyperBallSizes, vertexPartitioner, edges)
    } else {
      newHarmonicCentralities
    }
  }

  /** Returns hyperBallCounters for a diameter increased with 1.*/
  private def getNextHyperBalls(
    hyperBallCounters: SortedRDD[ID, HLL],
    vertexPartitioner: Partitioner,
    edges: UniqueSortedRDD[ID, Iterable[ID]]): UniqueSortedRDD[ID, HLL] = {
    // Aggregate the Hll counters for every neighbor.
    (hyperBallCounters
      .sortedJoin(edges)
      .flatMap {
        case (id, (hll, neighbors)) => neighbors.map(nid => (nid, hll))
        // Add the original Hlls.
      } ++ hyperBallCounters)
      // Note that the + operator is defined on Algebird's HLL.
      .reduceBySortedKey(vertexPartitioner, _ + _)
  }
}

