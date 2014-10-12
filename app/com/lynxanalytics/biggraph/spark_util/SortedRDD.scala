package com.lynxanalytics.biggraph.spark_util

import org.apache.spark.{ HashPartitioner, Partition, TaskContext }
import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.apache.spark.rdd._
import org.apache.spark.storage.RDDInfo
import scala.collection.SortedSet
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Stack
import scala.math.Ordering
import scala.reflect.ClassTag
import scala.util.Sorting

import com.lynxanalytics.biggraph.{ bigGraphLogger => bGLog }
import com.lynxanalytics.biggraph.spark_util.Implicits._

object SortedRDD {
  // Creates a SortedRDD from an unsorted but partitioned RDD.
  def fromUnsorted[K: Ordering, V](rdd: RDD[(K, V)]): SortedRDD[K, V] = {
    val arrayRDD = new SortedArrayRDD(rdd)
    new ArrayBackedSortedRDD(arrayRDD)
  }
}

private object SortedRDDUtil {
  def assertMatchingRDDs[K](first: SortedRDD[K, _], second: SortedRDD[K, _]): Unit = {
    assert(
      first.partitions.size == second.partitions.size,
      s"Size mismatch between $first and $second")
    assert(
      first.partitioner == second.partitioner,
      s"Partitioner mismatch between $first and $second")
  }

  def merge[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (V, W))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next; //bi2.next; // uncomment to disallow multiple keys on the left side
        (k1, (v1, v2)) #:: merge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        merge(bi1, bi2)
      } else {
        bi2.next
        merge(bi1, bi2)
      }
    } else {
      Stream()
    }
  }

  def leftOuterMerge[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (V, Option[W]))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next; //bi2.next; // uncomment to disallow multiple keys on the left side
        (k1, (v1, Some(v2))) #:: leftOuterMerge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        (k1, (v1, None)) #:: leftOuterMerge(bi1, bi2)
      } else {
        bi2.next
        leftOuterMerge(bi1, bi2)
      }
    } else if (bi1.hasNext) {
      bi1.toStream.map { case (k, v) => (k, (v, None)) }
    } else {
      Stream()
    }
  }

  def fullOuterMerge[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (Option[V], Option[W]))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next;
        bi2.next;
        (k1, (Some(v1), Some(v2))) #:: fullOuterMerge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        (k1, (Some(v1), None)) #:: fullOuterMerge(bi1, bi2)
      } else {
        bi2.next
        (k2, (None, Some(v2))) #:: fullOuterMerge(bi1, bi2)
      }
    } else if (bi1.hasNext) {
      bi1.toStream.map { case (k, v) => (k, (Some(v), None)) }
    } else if (bi2.hasNext) {
      bi2.toStream.map { case (k, v) => (k, (None, Some(v))) }
    } else {
      Stream()
    }
  }
}

// An RDD with each partition sorted by the key. "self" must already be sorted.
abstract class SortedRDD[K: Ordering, V] private[spark_util] (
  val self: RDD[(K, V)])
    extends RDD[(K, V)](self) {
  assert(
    self.partitioner.isDefined,
    s"$self was used to create a SortedRDD, but it wasn't partitioned")
  override def getPartitions: Array[Partition] = self.partitions
  override val partitioner = self.partitioner
  override def compute(split: Partition, context: TaskContext) = self.compute(split, context)

  // See comments at DerivedSortedRDD before blindly using this method!
  private def derive[R](derivation: DerivedSortedRDD.Derivation[K, V, R]) =
    new DerivedSortedRDD(this, derivation)

  // See comments at DerivedSortedRDD before blindly using this method!
  private def biDeriveWith[W, R](
    other: SortedRDD[K, W],
    derivation: BiDerivedSortedRDD.Derivation[K, V, W, R]) =
    new BiDerivedSortedRDD(this, other, derivation)

  /*
   * Differs from Spark's join implementation as this allows multiple keys only on the left side
   * the keys of 'other' must be unique!
   */
  def sortedJoin[W](other: SortedRDD[K, W]): SortedRDD[K, (V, W)] =
    biDeriveWith[W, (V, W)](
      other,
      { (first, second) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.merge(it1.buffered, it2.buffered).iterator
        }
      })

  /*
   * Differs from Spark's join implementation as this allows multiple keys only on the left side
   * the keys of 'other' must be unique!
   */
  def sortedLeftOuterJoin[W](other: SortedRDD[K, W]): SortedRDD[K, (V, Option[W])] =
    biDeriveWith[W, (V, Option[W])](
      other,
      { (first, second) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.leftOuterMerge(it1.buffered, it2.buffered).iterator
        }
      })

  /*
   * Returns an RDD with the union of the keyset of this and other. For each key it returns two
   * Options with the value for the key in this and in the other RDD.
   *
   * Both RDDs must have unique keys. Otherwise the world might end.
   */
  def fullOuterJoin[W](other: SortedRDD[K, W]): SortedRDD[K, (Option[V], Option[W])] =
    biDeriveWith[W, (Option[V], Option[W])](
      other,
      { (first: SortedRDD[K, V], second: SortedRDD[K, W]) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.fullOuterMerge(it1.buffered, it2.buffered).iterator
        }
      })

  def mapValues[U](f: V => U)(implicit ck: ClassTag[K], cv: ClassTag[V]): SortedRDD[K, U] =
    derive(_.self.mapValues(x => f(x)))

  def flatMapValues[U](
    f: V => TraversableOnce[U])(
      implicit ck: ClassTag[K], cv: ClassTag[V]): SortedRDD[K, U] =
    derive(_.self.flatMapValues(x => f(x)))

  // This version takes a Key-Value tuple as argument.
  def mapValuesWithKeys[U](f: ((K, V)) => U): SortedRDD[K, U] =
    derive(_.mapPartitions(
      { it => it.map { case (k, v) => (k, f(k, v)) } },
      preservesPartitioning = true))

  override def filter(f: ((K, V)) => Boolean): SortedRDD[K, V] =
    derive(_.self.filter(f))

  override def distinct: SortedRDD[K, V] =
    derive(
      _.mapPartitions({ it =>
        val bi = it.buffered
        new Iterator[(K, V)] {
          def hasNext = bi.hasNext
          def next() = {
            val n = bi.next
            while (bi.hasNext && bi.head == n) bi.next
            n
          }
        }
      }, preservesPartitioning = true))

  // `createCombiner`, which turns a V into a C (e.g., creates a one-element list)
  // `mergeValue`, to merge a V into a C (e.g., adds it to the end of a list)
  def combineByKey[C](createCombiner: V => C,
                      mergeValue: (C, V) => C): SortedRDD[K, C] =
    derive(
      _.mapPartitions({ it =>
        val bi = it.buffered
        new Iterator[(K, C)] {
          def hasNext = bi.hasNext
          def next() = {
            val n = bi.next
            var res = createCombiner(n._2)
            while (bi.hasNext && bi.head._1 == n._1) res = mergeValue(res, bi.next._2)
            n._1 -> res
          }
        }
      }, preservesPartitioning = true))

  def groupByKey(): SortedRDD[K, ArrayBuffer[V]] = {
    val createCombiner = (v: V) => ArrayBuffer(v)
    val mergeValue = (buf: ArrayBuffer[V], v: V) => buf += v
    combineByKey(createCombiner, mergeValue)
  }

  def takeFirstNValuesOrSo(n: Int): SortedRDD[K, V] = {
    val numPartitions = partitions.size
    val div = n / numPartitions
    val mod = n % numPartitions
    // Actually, normal assumptions of derive does NOT apply here. This means that if you have
    // an RDD x returned by this call and then you call y = x.restrictToIdSet,
    // you might see items in y not present x.
    derive(
      _.mapPartitionsWithIndex(
        { (pid, it) =>
          val elementsFromThisPartition = if (pid < mod) (div + 1) else div
          it.take(elementsFromThisPartition)
        },
        preservesPartitioning = true))
  }

  def collectFirstNValuesOrSo(n: Int)(implicit ct: ClassTag[V]): Seq[V] = {
    takeFirstNValuesOrSo(n)
      .map { case (k, v) => v }
      .collect
  }

  def restrictToIdSet(ids: IndexedSeq[K]): SortedRDD[K, V]

  def memorizeBackingArray(storageInfo: Array[RDDInfo]): Unit
}

// SortedRDD which was derived from one other sorted rdd without changing the id space.
// Also, the derivation must be such that the value of on item (k, v) in the result only depends
// on the value of items (k, ?) in the input. E.g. it can not depend on the ordinal of (k, ?)s in
// the input or values of other items.
// With other words, we assume that the order filter by id and derivation are exchangeable.
private[spark_util] object DerivedSortedRDD {
  type Derivation[K, VOld, VNew] = SortedRDD[K, VOld] => RDD[(K, VNew)]
}
private[spark_util] class DerivedSortedRDD[K: Ordering, VOld, VNew](
  source: SortedRDD[K, VOld],
  derivation: DerivedSortedRDD.Derivation[K, VOld, VNew])
    extends SortedRDD[K, VNew](derivation(source)) {

  def restrictToIdSet(ids: IndexedSeq[K]): SortedRDD[K, VNew] =
    new DerivedSortedRDD(source.restrictToIdSet(ids), derivation)

  def memorizeBackingArray(storageInfo: Array[RDDInfo]): Unit =
    source.memorizeBackingArray(storageInfo)
}

// SortedRDD which was derived from two other sorted rdds without changing the id space.
// See comments at DerivedSortedRDD, the same restrictions apply here.
private[spark_util] object BiDerivedSortedRDD {
  type Derivation[K, VOld1, VOld2, VNew] = (SortedRDD[K, VOld1], SortedRDD[K, VOld2]) => RDD[(K, VNew)]
}
private[spark_util] class BiDerivedSortedRDD[K: Ordering, VOld1, VOld2, VNew](
  source1: SortedRDD[K, VOld1],
  source2: SortedRDD[K, VOld2],
  derivation: BiDerivedSortedRDD.Derivation[K, VOld1, VOld2, VNew])
    extends SortedRDD[K, VNew](derivation(source1, source2)) {

  def restrictToIdSet(ids: IndexedSeq[K]): SortedRDD[K, VNew] =
    new BiDerivedSortedRDD(
      source1.restrictToIdSet(ids), source2.restrictToIdSet(ids), derivation)

  def memorizeBackingArray(storageInfo: Array[RDDInfo]): Unit = {
    source1.memorizeBackingArray(storageInfo)
    source2.memorizeBackingArray(storageInfo)
  }
}

// An RDD with each partition having a single value, an x: (Int, Array[(K, V)]). The x._1 is simply
// the partition id. x._2 is an array of key-value pairs sorted by key. data is assumed to be
// partitioned by a hash partitioner. A (k,v) in partition i of data will end up in the array in
// partition i of this SortedArrayRDD.
private[spark_util] class SortedArrayRDD[K: Ordering, V] private[spark_util] (data: RDD[(K, V)])
    extends RDD[(Int, Array[(K, V)])](data) {

  assert(
    data.partitioner.isDefined,
    s"$data was used to create a SortedArrayRDD, but it wasn't partitioned")

  assert(
    data.partitioner.get.isInstanceOf[HashPartitioner],
    s"$data was used to create a SortedArrayRDD, but it wasn't partitioned via a HashPartitioner")

  override def getPartitions: Array[Partition] = data.partitions
  override val partitioner = data.partitioner
  override def compute(split: Partition, context: TaskContext): Iterator[(Int, Array[(K, V)])] = {
    val it = data.compute(split, context)
    val array = it.toArray
    Sorting.quickSort(array)(Ordering.by[(K, V), K](_._1))
    Iterator((split.index, array))
  }

  def memorize(storageInfo: Array[RDDInfo]): Unit = {
    val fullyCached =
      storageInfo
        .find(_.id == id)
        .map(rddInfo => rddInfo.isCached && rddInfo.numCachedPartitions == rddInfo.numPartitions)
        .getOrElse(false)
    if (!fullyCached) {
      bGLog.info(s"PERF Loading to memory RDD: $this")
      cache()
      foreach(_ => ())
      bGLog.info(s"PERF RDD load completed: $id")
    } else {
      bGLog.info(s"PERF RDD $this found in memory")
    }
  }
}

private[spark_util] class ArrayBackedSortedRDD[K: Ordering, V](arrayRDD: SortedArrayRDD[K, V])
    extends SortedRDD[K, V](
      arrayRDD.mapPartitions(
        it => it.next._2.iterator,
        preservesPartitioning = true)) {
  def restrictToIdSet(ids: IndexedSeq[K]): SortedRDD[K, V] =
    new RestrictedArrayBackedSortedRDD(arrayRDD, ids)

  def memorizeBackingArray(storageInfo: Array[RDDInfo]): Unit =
    arrayRDD.memorize(storageInfo)

  override def setName(newName: String): this.type = {
    name = newName
    arrayRDD.name = s"Backing ArrayRDD of $newName"
    this
  }
}
private object BinarySearchUtils {
  // Finds the range in a sorted array where all ids == a given id, assuming the range must be
  // in the given [startIdx, endIdx) range.
  // If the id is not found in the array, it returns an empty range with elemnts to the left
  // of the range being strictly less while elements to the right being strictly more than id.
  // Both input and output start indexes are inclusive, end indexes are exclusive.
  def findRange[K: Ordering, V](
    array: Array[(K, V)], id: K, startIdx: Int, endIdx: Int): (Int, Int) = {

    val ord = implicitly[Ordering[K]]
    import ord.mkOrderingOps

    if (startIdx == endIdx) (startIdx, endIdx)
    else {
      val midIdx = (startIdx + endIdx) / 2
      val midId = array(midIdx)._1
      if (midId < id) {
        var newStartIdx = midIdx + 1
        // Let's find the first strictly larger element.
        while ((newStartIdx < endIdx) && (array(newStartIdx)._1 == midId)) newStartIdx += 1
        findRange(array, id, newStartIdx, endIdx)
      } else if (midId > id) {
        var newEndIdx = midIdx
        // Let's find the first element == midId.
        while ((newEndIdx > startIdx) && (array(newEndIdx - 1)._1 == midId)) newEndIdx -= 1
        findRange(array, id, startIdx, newEndIdx)
      } else {
        // Bingo!
        var resStartIdx = midIdx
        // Let's find the first element equal to id.
        while ((resStartIdx > startIdx) && (array(resStartIdx - 1)._1 == id)) resStartIdx -= 1
        var resEndIdx = midIdx
        // Let's find the first element greater than id.
        while ((resEndIdx < endIdx) && (array(resEndIdx)._1 == id)) resEndIdx += 1
        (resStartIdx, resEndIdx)
      }
    }
  }

  def findIds[K: Ordering, V](
    array: Array[(K, V)], ids: IndexedSeq[K], startIdx: Int, endIdx: Int): Iterator[(K, V)] = {

    if (ids.size == 0) Iterator()
    else {
      val midIdIdx = ids.size / 2
      val midId = ids(midIdIdx)
      val midRange = findRange(array, midId, startIdx, endIdx)
      findIds(array, ids.slice(0, midIdIdx), startIdx, midRange._1) ++
        array.view(midRange._1, midRange._2).iterator ++
        findIds(array, ids.slice(midIdIdx + 1, ids.size), midRange._2, endIdx)
    }
  }
}

object RestrictedArrayBackedSortedRDD {
  def restrictArrayRDDToIds[K: Ordering, V](
    arrayRDD: SortedArrayRDD[K, V],
    ids: IndexedSeq[K]): RDD[(K, V)] = {
    val partitioner = arrayRDD.partitioner.get
    arrayRDD.mapPartitionsWithIndex(
      { (pid, it) =>
        val array = it.next._2
        val idsInThisPartition = ids.filter(id => partitioner.getPartition(id) == pid)
        BinarySearchUtils.findIds[K, V](array, idsInThisPartition, 0, array.size)
      },
      preservesPartitioning = true)
  }
}
private[spark_util] class RestrictedArrayBackedSortedRDD[K: Ordering, V](
  arrayRDD: SortedArrayRDD[K, V],
  ids: IndexedSeq[K])
    extends SortedRDD[K, V](RestrictedArrayBackedSortedRDD.restrictArrayRDDToIds(arrayRDD, ids)) {
  def restrictToIdSet(newIds: IndexedSeq[K]): SortedRDD[K, V] =
    new RestrictedArrayBackedSortedRDD(arrayRDD, ids.intersect(newIds))

  def memorizeBackingArray(storageInfo: Array[RDDInfo]): Unit =
    arrayRDD.memorize(storageInfo)
}
