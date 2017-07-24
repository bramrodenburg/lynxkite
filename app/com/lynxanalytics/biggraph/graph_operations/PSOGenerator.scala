// Generates scale-free graph based on probability x similarity model.
// The degree distribution of the resuting graph will be scale-free and
// it will have high average clustering.
package com.lynxanalytics.biggraph.graph_operations

import scala.math
import scala.util.Random
import scala.collection.mutable.PriorityQueue
import org.apache.spark.rdd.RDD
import com.lynxanalytics.biggraph.spark_util.SortedRDD
import com.lynxanalytics.biggraph.spark_util.UniqueSortedRDD

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object PSOGenerator extends OpFromJson {

  class Output(implicit instance: MetaGraphOperationInstance) extends MagicOutput(instance) {

    val (vs, es) = graph
    val radial = vertexAttribute[Double](vs)
    val angular = vertexAttribute[Double](vs)
  }
  def fromJson(j: JsValue) = PSOGenerator(
    (j \ "size").as[Long],
    (j \ "externalDegree").as[Int],
    (j \ "internalDegree").as[Int],
    (j \ "exponent").as[Double],
    (j \ "seed").as[Long])
}
import PSOGenerator._
case class PSOGenerator(size: Long, externalDegree: Int, internalDegree: Int, exponent: Double,
                        seed: Long) extends TypedMetaGraphOp[NoInput, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new NoInput

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance)
  override def toJson = Json.obj(
    "size" -> size,
    "externalDegree" -> externalDegree,
    "internalDegree" -> internalDegree,
    "exponent" -> exponent,
    "seed" -> seed)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val partitioner = rc.partitionerForNRows(size)
    val ordinals = rc.sparkContext.parallelize(0L until size, partitioner.numPartitions)
    val vertices = ordinals.randomNumbered(partitioner)
    val sc = rc.sparkContext
    val masterRandom = new Random(seed)
    val numVertices = vertices.count
    val logNumVertices: Double = math.log(numVertices.toDouble)
    // Adds the necessary attributes for later calculations.
    val reorderedID = vertices.zipWithIndex.map { case ((key, nothing), reID) => (key, reID + 1) }
    val radialAdded = reorderedID.map { case (key, reID) => (key, (reID, 2 * math.log(reID.toDouble))) }
    val radial = radialAdded.map { case (key, (reID, radial)) => (key, radial) }
    val angularAdded = radialAdded.map({ case (key, (reID, rad)) => (key, (reID, rad, masterRandom.nextDouble * math.Pi * 2)) })
    val angular = angularAdded.map { case (key, (reID, radial, angular)) => (key, angular) }
    val expectedSamples = angularAdded.map {
      case (key, (reID, rad, ang)) => (key, (reID, rad, ang,
        totalExpectedEPSO(exponent, externalDegree, internalDegree, numVertices, reID)))
    }
    // key | reorderedID | radial | angular | expectedDegree
    // This next part replaces the double-linked list found in the local implementation.
    // Groups the samples for each vertex into a list. The first element of these are the vertices, then 
    // angular samples from cloclwise-most to counterclockwise-most, then radial samples.
    // Note: sample list will include the node itself in the middle.
    val allVerticesList: List[(Long, Long, Double, Double, Double)] = expectedSamples.map {
      case (key, (reID, rad, ang, eSam)) => (key, reID, rad, ang, eSam)
    }.collect().sortBy(_._4).toList
    val possibilityList: List[List[(Long, Long, Double, Double, Double)]] = {
      val numFirstSamples: Int = (math.round(logNumVertices * allVerticesList.head._5)).toInt
      val endofVerticesList = allVerticesList.reverse.take(numFirstSamples)
      var resultList: List[List[(Long, Long, Double, Double, Double)]] = Nil
      var remainderList: List[(Long, Long, Double, Double, Double)] = allVerticesList
      var i = numFirstSamples
      while (i > 0 && !remainderList.isEmpty) remainderList = remainderList.tail
      var angularSampleList: List[(Long, Long, Double, Double, Double)] = endofVerticesList ++ allVerticesList.take(numFirstSamples)
      var radialSampleList: List[(Long, Long, Double, Double, Double)] = allVerticesList.head :: Nil
      resultList = (allVerticesList.head :: angularSampleList) :: resultList
      if (!allVerticesList.isEmpty) {
        for (vertex <- allVerticesList.tail) {
          val numCurrentSamples: Int = (math.round(logNumVertices * vertex._5)).toInt
          if (!radialSampleList.isEmpty) radialSampleList = radialSampleList.take(numCurrentSamples)
          radialSampleList = vertex :: radialSampleList

          // Once remainderList reaches the end makes it circular by sampling the beginning again.
          if (remainderList.isEmpty) remainderList = allVerticesList.take(numCurrentSamples)
          if (!angularSampleList.isEmpty) angularSampleList = angularSampleList.take(numCurrentSamples * 2)
          // Keeps the sample list centered on 'vertex'.
          if (numCurrentSamples >= (math.round(logNumVertices * remainderList.head._5)).toInt) {
            angularSampleList = remainderList.head :: angularSampleList
          }
          remainderList = remainderList.tail

          val vertexResult = vertex :: (angularSampleList ++ radialSampleList)
          resultList = vertexResult :: resultList
        }
      }
      resultList
    }
    // Selects the expectedDegree smallest distance edges from possibility bundles.
    val possibilities = sc.parallelize(possibilityList)
    val esBase = possibilities.map {
      case (data) =>
        {
          var resultEdges: List[(Long, Long)] = Nil
          val numSelections: Double = data.head._5
          val numSamples: Int = (math.round(logNumVertices * numSelections)).toInt
          val srcTuple = data.head
          // hyperbolicDistance | src key | dst key
          //TODO instead of a maxheap look into using rdd.top(numSelections)?
          // RDD orders by keys though. Make probability the key?
          val maxHeap = PriorityQueue.empty(Ordering.by[(Double, Long, Long), Double](_._1))
          def heapElement(srcTuple: (Long, Long, Double, Double, Double),
                          dstTuple: (Long, Long, Double, Double, Double)): (Double, Long, Long) = {
            (-hyperbolicDistance(srcTuple._3, dstTuple._3, srcTuple._4, dstTuple._4),
              srcTuple._1, dstTuple._1)
          }
          //This could be parallelized and 'for' probably doesn't do it.
          if (!data.tail.isEmpty) {
            for (dstTuple <- data.tail) {
              if (srcTuple != dstTuple) maxHeap += heapElement(srcTuple, dstTuple)
            }
          }
          for (j <- 0 until numSelections.toInt) {
            val result = maxHeap.dequeue
            resultEdges = (result._2, result._3) :: resultEdges
          }
          resultEdges
        }
    }.flatMap(identity)
    val es = (esBase.map { case (edge1, edge2) => Edge(edge1, edge2) } ++
      esBase.map { case (edge1, edge2) => Edge(edge2, edge1) })
    output(o.vs, vertices.mapValues(_ => ()))
    output(o.radial, radial.sortUnique(partitioner))
    output(o.angular, angular.sortUnique(partitioner))
    output(o.es, es.randomNumbered(partitioner))
  }
  // Returns hyperbolic distance.
  def hyperbolicDistance(rad1: Double, rad2: Double, ang1: Double, ang2: Double): Double = {
    rad1 + rad2 + 2 * math.log(phi(ang1, ang2) / 2)
  }
  // Returns angular component for hyperbolic distance calculation.
  def phi(ang1: Double, ang2: Double): Double = {
    math.Pi - math.abs(math.Pi - math.abs(ang1 - ang2))
  }
  // Expected number of internal connections at given time in the E-PSO model.
  def internalConnectionsEPSO(exponent: Double,
                              internalLinks: Int,
                              maxNodes: Long,
                              currentNodeID: Long): Double = {
    val firstPart: Double = ((2 * internalLinks.toDouble * (1 - exponent)) /
      (math.pow(1 - math.pow(maxNodes.toDouble, -(1 - exponent)), 2) * (2 * exponent - 1)))
    val secondPart: Double = math.pow((maxNodes / currentNodeID.toDouble), 2 * exponent - 1) - 1
    val thirdPart: Double = (1 - math.pow(currentNodeID.toDouble, -(1 - exponent)))
    firstPart * secondPart * thirdPart
  }
  // Expected number of connections at given time in the E-PSO model.
  def totalExpectedEPSO(exponent: Double,
                        externalLinks: Int,
                        internalLinks: Int,
                        maxNodes: Long,
                        currentNodeID: Long): Double = {
    externalLinks + internalConnectionsEPSO(exponent, internalLinks, maxNodes, currentNodeID)
  }
}
