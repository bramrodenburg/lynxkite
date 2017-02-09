package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import org.apache.spark.api.java.StorageLevels
import org.apache.spark.rdd.RDD

import scala.collection.immutable.Seq
import scala.util.Random

// The sampling algorithm works as follows.
//
// 1) Visit some of the nodes and edges by the following logic
//  `numOfStartPoints` times
//      select a random start node
//      `numOfWalksFromOnePoint` times
//          perform a random walk on the graph from the selected node where the length of the walk
//          follows a geometric distribution with parameter `walkAbortionProbability`
//
// 2) Assign an index to every node and edge: the earlier the node was visited / edge was traversed
//    during the previous point, the smaller the index is. The index of nodes / edges never visited
//    is higher than the index of visited ones.
//
// 3) A sample can be obtained by filtering out nodes and edges of high indices. This index limit
//    has to be determined by the user.
//
// The actual implementation is different (since it has to be parallel) but the resulting indices
// are identical to the method described above.
object RandomWalkSample extends OpFromJson {
  class Input extends MagicInputSignature {
    val (vs, es) = graph
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val vertexFirstVisited = vertexAttribute[Double](inputs.vs.entity)
    val edgeFirstTraversed = edgeAttribute[Double](inputs.es.entity)
  }
  def fromJson(j: JsValue) = RandomWalkSample(
    (j \ "numOfStartPoints").as[Int],
    (j \ "numOfWalksFromOnePoint").as[Int],
    (j \ "walkAbortionProbability").as[Double],
    (j \ "seed").as[Int])
}
import RandomWalkSample._
case class RandomWalkSample(numOfStartPoints: Int, numOfWalksFromOnePoint: Int,
                            walkAbortionProbability: Double, seed: Int)
    extends TypedMetaGraphOp[Input, Output] {
  assert(walkAbortionProbability < 1.0,
    "The probability of aborting a walk at RandomWalkSample must be smaller than 1.0")
  assert(walkAbortionProbability >= 0.01,
    "The probability of aborting a walk at RandomWalkSample can not be lower than 0.01")

  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  override def toJson = Json.obj(
    "numOfStartPoints" -> numOfStartPoints,
    "numOfWalksFromOnePoint" -> numOfWalksFromOnePoint,
    "walkAbortionProbability" -> walkAbortionProbability,
    "seed" -> seed)

  private type StepIdx = Double
  object StepIdx {
    val MaxValue = scala.Double.MaxValue
  }
  private type RemainingSteps = Int
  private type WalkState = (ID, (StepIdx, RemainingSteps))

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val runtimeContext = rc
    val nodes = inputs.vs.rdd
    val edges = inputs.es.rdd
    val rnd = new Random(seed)

    var multiWalkState: RDD[WalkState] = {
      // The run time of the sampling algorithm is proportional to the length of the longest walk.
      // To avoid long run times we cheat and don't wait very long for abortion but force it after
      // some steps. To set this artificial limit, we use the heuristics
      //    2 times the expected length of a walk = 2 / walkAbortionProbability
      // where 2 is an arbitrary number
      val maxStepsWithoutAbortion = (2 / walkAbortionProbability).toInt
      val walksToPerform = for {
        _ <- 0 until numOfStartPoints
        fromNode = randomNode(nodes, rnd.nextLong())
        _ <- 0 until numOfWalksFromOnePoint
        walkLength = geometric(rnd, p = walkAbortionProbability) min maxStepsWithoutAbortion
      } yield (fromNode, walkLength)
      val cumulativeWalkLength = walksToPerform.scanLeft(0L)(_ + _._2)
      val initialState: Seq[WalkState] = walksToPerform.zip(cumulativeWalkLength).map {
        // index of the first step of a walk = the sum of the length of all previous walks
        // remainingSteps = walkLength - 1, since we count the start node as well
        case ((node, walkLength), sumOfLengthOfPreviousWalks) =>
          (node, (sumOfLengthOfPreviousWalks.toDouble, walkLength - 1))
      }
      rc.sparkContext.parallelize(initialState, nodes.partitioner.get.numPartitions)
    }
    multiWalkState.persist(StorageLevels.DISK_ONLY)

    var stepIdxWhenNodeFirstVisited = {
      val allUnvisited = nodes.mapValues(_ => StepIdx.MaxValue)
      minByKey(allUnvisited, multiWalkState.map { case (node, (idx, _)) => (node, idx) })
    }
    var tmpN = stepIdxWhenNodeFirstVisited
    var stepIdxWhenEdgeFirstTraversed: RDD[(ID, StepIdx)] = edges.mapValues(_ => StepIdx.MaxValue)
    var tmpE = stepIdxWhenEdgeFirstTraversed
    val step = multiStepper(nodes, edges)

    var stepCnt = 1
    while (!multiWalkState.isEmpty()) {
      val (nextState, edgesTraversed) = step(multiWalkState, rnd.nextInt())
      nextState.persist(StorageLevels.DISK_ONLY)

      stepIdxWhenNodeFirstVisited = minByKey(stepIdxWhenNodeFirstVisited,
        nextState.map { case (node, (idx, _)) => (node, idx) })
      stepIdxWhenEdgeFirstTraversed = minByKey(stepIdxWhenEdgeFirstTraversed, edgesTraversed)

      // The length of the lineage of `stepIdxWhenNodeFirstVisited` and
      // `stepIdxWhenEdgeFirstTraversed` grows as the Fibonacci numbers. Therefore we have to cut
      // that lineage periodically with `RDD#localCheckpoint`. This reduce resilience but prevents
      // StackOverflowErrors
      if (stepCnt % 20 == 0) {
        stepIdxWhenNodeFirstVisited.localCheckpoint()
        stepIdxWhenNodeFirstVisited.count()
        stepIdxWhenEdgeFirstTraversed.localCheckpoint()
        stepIdxWhenEdgeFirstTraversed.count()
        tmpN.unpersist(blocking = false)
        tmpE.unpersist(blocking = false)
        tmpN = stepIdxWhenNodeFirstVisited
        tmpE = stepIdxWhenEdgeFirstTraversed
      }

      multiWalkState.unpersist(blocking = false)
      multiWalkState = nextState
      stepCnt += 1
    }

    val vs = stepIdxWhenNodeFirstVisited.sort(nodes.partitioner.get).asUniqueSortedRDD
    val es = stepIdxWhenEdgeFirstTraversed.sort(edges.partitioner.get).asUniqueSortedRDD
    output(o.vertexFirstVisited, vs)
    output(o.edgeFirstTraversed, es)
  }

  def multiStepper(nodes: VertexSetRDD, edges: EdgeBundleRDD):
    (RDD[WalkState], Int) => (RDD[WalkState], RDD[(ID, StepIdx)]) =
  {
    val outEdgesPerNode = edges.map {
      case (edgeId, Edge(src, dest)) => src -> (dest, edgeId)
    }.groupByKey().map {
      case (id, it) => (id, it.toArray)
    }.sortUnique(nodes.partitioner.get)
    outEdgesPerNode.persist(StorageLevels.DISK_ONLY)

    def continueWalk(walkState: WalkState): Boolean = walkState._2._2 > 0

    def step(multiWalkState: RDD[WalkState], seed: Int): (RDD[WalkState], RDD[(ID, StepIdx)]) = {
      val nextState = multiWalkState.filter(continueWalk).
        partitionBy(outEdgesPerNode.partitioner.get).
        sort(outEdgesPerNode.partitioner.get).
        sortedJoin(outEdgesPerNode).mapPartitionsWithIndex {
          case (pid, it) =>
            val rnd = new Random((pid << 16) + seed)
            it.map {
              case (_, ((idx, remainingSteps), edgesFromHere)) =>
                val rndIdx = rnd.nextInt(edgesFromHere.length)
                val (toNode, onEdge) = edgesFromHere(rndIdx)
                val stepIdx = idx + 1
                ((toNode, (stepIdx, remainingSteps - 1)), (onEdge, stepIdx))
            }
        }
      nextState.persist(StorageLevels.DISK_ONLY)

      (nextState.map(_._1), nextState.map(_._2))
    }

    step
  }

  private def randomNode(nodes: VertexSetRDD, seed: Long) =
    nodes.takeSample(withReplacement = false, 1, seed).head._1

  private def minByKey(keyValue1: RDD[(ID, StepIdx)],
                       keyValue2: RDD[(ID, StepIdx)]): RDD[(ID, StepIdx)] = {
    val x = keyValue2.reduceByKey(_ min _)
    keyValue1.leftOuterJoin(x).mapValues {
      case (oldIdx, newIdxOpt) => oldIdx min newIdxOpt.getOrElse(StepIdx.MaxValue)
    }
  }

  // Draws a random number from a geometric distribution with parameter p
  // http://math.stackexchange.com/questions/485448
  private def geometric(rnd: Random, p: Double): Int =
    (Math.log(rnd.nextDouble()) / Math.log(1 - p)).toInt + 1
}
