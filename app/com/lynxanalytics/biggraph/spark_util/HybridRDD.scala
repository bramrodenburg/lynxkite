// Assorted utilities for working with RDDs.
package com.lynxanalytics.biggraph.spark_util

import com.lynxanalytics.biggraph.graph_api.RuntimeContext
import com.lynxanalytics.biggraph.graph_util.LoggedEnvironment
import org.apache.spark
import org.apache.spark.rdd.RDD
import scala.reflect._

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.graph_api.io.EntityIO

object HybridRDD {
  // If the threshold is not defined use a number sufficiently smaller than vertices per partition.
  // If we pick vertices per partition the partition load is going to be uneven for the sortedJoin
  // in joinLookup. If we pick a too small threshold the largeKeysSet is going to be too large.
  // In the worst case largeKeysSet can have sourceRDD size / threshold elements.
  private val hybridLookupThreshold = util.Properties.envOrElse(
    "KITE_HYBRID_LOOKUP_THRESHOLD", s"${EntityIO.verticesPerPartition / 5}").toInt

  // A lookup method based on joining the source RDD with the lookup table. Assumes
  // that each key has only so many instances that we can handle all of them in a single partition.
  private def joinLookup[K: Ordering: ClassTag, T: ClassTag, S](
    leftRDD: SortedRDD[K, T], lookupRDD: UniqueSortedRDD[K, S]): RDD[(K, (T, S))] = {
    assert(leftRDD.partitioner.get eq lookupRDD.partitioner.get,
      "LeftRDD and lookupRDD must have the same partitioner.")
    leftRDD.sortedJoin(lookupRDD)
  }

  // A lookup method based on sending the lookup table to all tasks. The lookup table should be
  // reasonably small.
  private def smallTableLookup[K: Ordering: ClassTag, T: ClassTag, S](
    leftRDD: RDD[(K, T)], lookupTable: Map[K, S]): RDD[(K, (T, S))] = {
    leftRDD
      .flatMap { case (key, tValue) => lookupTable.get(key).map(sValue => key -> (tValue, sValue)) }
  }
}

// A wrapping class for potentially skewed RDDs. Skewed means the cardinality of keys
// is extremely unevenly distributed.
case class HybridRDD[K: Ordering: ClassTag, T: ClassTag](
    // The large potentially skewed RDD to do joins on.
    sourceRDD: RDD[(K, T)],
    // A partitioner good enough for the sourceRDD. All RDDs used in the lookup methods
    // must have the same partitioner.
    partitioner: spark.Partitioner,
    // The RDD is distributed evenly, both in terms of the sizes of the partitions and the
    // distribution of the keys per partition.
    even: Boolean,
    // The threshold to decide whether this HybridRDD is skewed.
    threshold: Int = HybridRDD.hybridLookupThreshold)(
        implicit rc: RuntimeContext) {

  private val larges = {
    val numPartitions = sourceRDD.partitions.size
    val (rdd, sampleRatio) = if (even && numPartitions > 0) {
      // Assumes that the keys are distributed evenly among the partitions.
      val numSamplePartitions = rc.numSamplePartitions min numPartitions
      (new PartialRDD(sourceRDD, numSamplePartitions),
        numPartitions.toDouble / numSamplePartitions)
    } else {
      (sourceRDD, 1.0)
    }
    rdd
      .mapValues(_ => 1l)
      .reduceByKey(_ + _)
      .mapValues(x => (x * sampleRatio).toLong)
      .filter(_._2 > threshold)
      .collect
  }

  // True iff this HybridRDD has keys with large cardinalities.
  val isSkewed = !larges.isEmpty

  private val (largeKeysSet, largeKeysCoverage) = if (!isSkewed) {
    (Set.empty[K], 0L)
  } else {
    (larges.map(_._1).toSet, larges.map(_._2).reduce(_ + _))
  }
  // The RDD containing only keys that are safe to use in sorted join.
  import Implicits._
  private val smallKeysRDD: SortedRDD[K, T] = {
    if (isSkewed) {
      sourceRDD.filter { case (key, _) => !largeKeysSet.contains(key) }
    } else {
      sourceRDD
    }
  }.sort(partitioner)
  // The RDD to use with map lookup. It may contain keys with large cardinalities.
  private val largeKeysRDD: RDD[(K, T)] = if (isSkewed) {
    sourceRDD
  } else {
    null
  }

  // Caches the smallKeysRDD and the largeKeysRDD for skewed HybridRDDs.
  def persist(storageLevel: spark.storage.StorageLevel): Unit = {
    if (isSkewed) {
      largeKeysRDD.persist(storageLevel)
    }
    smallKeysRDD.persist(storageLevel)
  }

  // Same as lookup but repartitions the result after a hybrid lookup. The elements of the
  // result RDD are evenly distributed among its partitions.
  def lookupAndRepartition[S](
    lookupRDD: UniqueSortedRDD[K, S]): RDD[(K, (T, S))] = {
    val result = lookup(lookupRDD)
    if (isSkewed) {
      // "ord = null" is a workaround for a Scala 2.10 compiler bug.
      // TODO: Remove when upgrading to 2.11.
      result.repartition(sourceRDD.partitions.size)(ord = null)
    } else {
      result
    }
  }

  // A lookup method that does smallTableLookup for a few keys that have too many instances to
  // be handled by joinLookup and does joinLookup for the rest. There are no guarantees about the
  // partitions of the result RDD.
  def lookup[S](
    lookupRDD: UniqueSortedRDD[K, S]): RDD[(K, (T, S))] = {

    val smalls = HybridRDD.joinLookup(smallKeysRDD, lookupRDD)
    if (isSkewed) {
      val largeKeysMap = lookupRDD.filter(largeKeysSet contains _._1).collect.toMap
      log.info(s"Hybrid lookup found ${largeKeysSet.size} large keys covering "
        + s"${largeKeysCoverage} source records.")
      val larges = HybridRDD.smallTableLookup(largeKeysRDD, largeKeysMap)
      smalls ++ larges
    } else {
      smalls // For non-skewed RDDs every row is in smallKeysRDD.
    }
  }
}
