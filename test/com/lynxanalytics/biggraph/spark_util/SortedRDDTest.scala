package com.lynxanalytics.biggraph.spark_util

import org.scalatest.FunSuite
import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext.rddToPairRDDFunctions
import com.lynxanalytics.biggraph.TestSparkContext
import com.lynxanalytics.biggraph.Timed

class SortedRDDTest extends FunSuite with TestSparkContext {
  import Implicits._

  test("join without intersection") {
    val p = new HashPartitioner(4)
    val a = sparkContext.parallelize(10 to 15).map(x => (x, x)).partitionBy(p).toSortedRDD
    val b = sparkContext.parallelize(20 to 25).map(x => (x, x)).partitionBy(p).toSortedRDD
    val j: SortedRDD[Int, (Int, Int)] = a.sortedJoin(b)
    assert(j.collect.toSeq == Seq())
  }

  test("join with intersection") {
    val p = new HashPartitioner(4)
    val a = sparkContext.parallelize(10 to 15).map(x => (x, x + 1)).partitionBy(p).toSortedRDD
    val b = sparkContext.parallelize(15 to 25).map(x => (x, x + 2)).partitionBy(p).toSortedRDD
    val j: SortedRDD[Int, (Int, Int)] = a.sortedJoin(b)
    assert(j.collect.toSeq == Seq(15 -> (16, 17)))
  }

  test("join with multiple keys on the left side") {
    val p = new HashPartitioner(4)
    val a = sparkContext.parallelize(Seq(0 -> 1, 10 -> 11, 10 -> 12, 11 -> 10, 20 -> 12)).partitionBy(p).toSortedRDD
    val b = sparkContext.parallelize(5 to 15).map(x => (x, "A")).partitionBy(p).toSortedRDD
    val sj: SortedRDD[Int, (Int, String)] = a.sortedJoin(b)
    val j: RDD[(Int, (Int, String))] = a.join(b)
    assert(sj.count == j.count)
  }

  // at the moment our sortedJoin does not support this feature
  ignore("join with multiple keys on both sides") {
    val p = new HashPartitioner(4)
    val a = sparkContext.parallelize(Seq(0 -> 1, 10 -> 11, 10 -> 12, 11 -> 10, 20 -> 12)).partitionBy(p).toSortedRDD
    val b = sparkContext.parallelize(Seq(10 -> "A", 10 -> "B")).partitionBy(p).toSortedRDD
    val sj: SortedRDD[Int, (Int, String)] = a.sortedJoin(b)
    val j: RDD[(Int, (Int, String))] = a.join(b)
    println(sj.collect.toSeq)
    println(j.collect.toSeq)
    assert(sj.count == j.count)
  }

  test("distinct") {
    val p = new HashPartitioner(4)
    val a = sparkContext.parallelize((1 to 5) ++ (3 to 7)).map(x => (x, x)).partitionBy(p).toSortedRDD
    val d: SortedRDD[Int, Int] = a.distinct
    assert(d.keys.collect.toSeq.sorted == (1 to 7))
  }

  def genData(parts: Int, rows: Int, seed: Int): RDD[(Long, Char)] = {
    val raw = sparkContext.parallelize(1 to parts, parts).mapPartitionsWithIndex {
      (i, it) => new util.Random(i + seed).alphanumeric.take(rows).iterator
    }
    val partitioner = new HashPartitioner(raw.partitions.size)
    raw.zipWithUniqueId.map { case (v, id) => id -> v }.partitionBy(partitioner)
  }

  ignore("benchmark join", com.lynxanalytics.biggraph.Benchmark) {
    class Demo(parts: Int, rows: Int) {
      val data = genData(parts, rows, 1).toSortedRDD.cache
      data.calculate
      val other = genData(parts, rows, 2).sample(false, 0.5, 0)
        .partitionBy(data.partitioner.get).toSortedRDD.cache
      other.calculate
      def oldJoin = getSum(data.join(other))
      def newJoin = getSum(data.sortedJoin(other))
      def getSum(rdd: RDD[(Long, (Char, Char))]) = rdd.mapValues { case (a, b) => a compare b }.values.reduce(_ + _)
    }
    val parts = 4
    val table = "%10s | %10s | %10s"
    println(table.format("rows", "old (ms)", "new (ms)"))
    for (round <- 10 to 20) {
      val rows = 100000 * round
      val demo = new Demo(parts, rows)
      val mew = Timed(demo.newJoin)
      val old = Timed(demo.oldJoin)
      println(table.format(parts * rows, old.nanos / 1000000, mew.nanos / 1000000))
      assert(mew.value == old.value)
    }
  }

  test("benchmark partition+sort+join", com.lynxanalytics.biggraph.Benchmark) {
    class Demo(parts: Int, rows: Int) {
      val data = genData(parts, rows, 1).cache
      data.calculate
      val other = genData(parts, rows, 2).sample(false, 0.5, 0).cache
      other.calculate
      assert(data.partitioner != other.partitioner)
      def oldJoin = getSum(data.join(other).toSortedRDD)
      def newJoin = getSum(data.toSortedRDD.sortedJoin(other.partitionBy(data.partitioner.get).toSortedRDD))
      def getSum(rdd: SortedRDD[Long, (Char, Char)]) = rdd.mapValues { case (a, b) => a compare b }.values.reduce(_ + _)
    }
    val parts = 4
    val table = "%10s | %10s | %10s"
    println(table.format("rows", "old (ms)", "new (ms)"))
    for (round <- 10 to 20) {
      val rows = 100000 * round
      val demo = new Demo(parts, rows)
      val mew = Timed(demo.newJoin)
      val old = Timed(demo.oldJoin)
      println(table.format(parts * rows, old.nanos / 1000000, mew.nanos / 1000000))
      assert(mew.value == old.value)
    }
  }

  test("benchmark leftOuterJoin if right is not sorted", com.lynxanalytics.biggraph.Benchmark) {
    class Demo(parts: Int, rows: Int, frac: Double) {
      val data = genData(parts, rows, 1).toSortedRDD.cache
      data.calculate
      val other = genData(parts, rows, 2).sample(false, frac, 0)
        .partitionBy(data.partitioner.get).cache
      other.calculate
      def oldJoin = getSum(data.leftOuterJoin(other).mapValues { case (a, b) => a -> b.getOrElse('0') }.toSortedRDD)
      def newJoin = getSum(data.sortedLeftOuterJoin(other.toSortedRDD).mapValues { case (a, b) => a -> b.getOrElse('0') }.asSortedRDD)
      def getSum(rdd: RDD[(Long, (Char, Char))]) = rdd.mapValues { case (a, b) => a compare b }.values.reduce(_ + _)
    }
    val parts = 4
    val rows = 100000
    val table = "%10s | %10s | %10s"
    println(table.format("frac", "old (ms)", "new (ms)"))
    for (round <- 1 to 10) {
      val frac = round * 0.1
      val demo = new Demo(parts, rows, frac)
      val mew = Timed(demo.newJoin)
      val old = Timed(demo.oldJoin)
      println(table.format(frac, old.nanos / 1000000, mew.nanos / 1000000))
      assert(mew.value == old.value)
    }
  }

  test("benchmark distinct", com.lynxanalytics.biggraph.Benchmark) {
    class Demo(parts: Int, rows: Int) {
      val sorted = genData(parts, rows, 1).values.map(x => (x, x))
        .partitionBy(new HashPartitioner(parts)).toSortedRDD.cache
      sorted.calculate
      val vanilla = sorted.map(x => x).cache
      vanilla.calculate
      def oldDistinct = vanilla.distinct.collect.toSeq.sorted
      def newDistinct = sorted.distinct.collect.toSeq.sorted
    }
    val parts = 4
    val table = "%10s | %10s | %10s"
    println(table.format("rows", "old (ms)", "new (ms)"))
    for (round <- 10 to 20) {
      val rows = 100000 * round
      val demo = new Demo(parts, rows)
      val mew = Timed(demo.newDistinct)
      val old = Timed(demo.oldDistinct)
      println(table.format(parts * rows, old.nanos / 1000000, mew.nanos / 1000000))
      assert(mew.value == old.value)
    }
  }

  test("sorted filter") {
    val sorted = genData(4, 1000, 1).values.map(x => (x, x))
      .partitionBy(new HashPartitioner(4)).toSortedRDD
    val filtered = sorted.filter(_._2 != 'a')
    assert(sorted.count > filtered.count)
  }

  test("sorted mapValues") {
    val sorted = genData(4, 1000, 1).values.map(x => (x, x))
      .partitionBy(new HashPartitioner(4)).toSortedRDD
    val mew = sorted.mapValues(x => 'a')
    val mewKeys = sorted.mapValuesWithKeys(x => 'a')
    val old = sorted.map(x => x).mapValues(x => 'a')
    assert(mew.collect.toMap == old.collect.toMap)
    assert(mew.collect.toMap == mewKeys.collect.toMap)
  }

  test("benchmark mapValues with keys", com.lynxanalytics.biggraph.Benchmark) {
    class Demo(parts: Int, rows: Int) {
      val sorted = genData(parts, rows, 1).values.map(x => (x, x))
        .partitionBy(new HashPartitioner(parts)).toSortedRDD.cache
      sorted.calculate
      def oldMV = sorted.map({ case (id, x) => id -> (id, x) }).partitionBy(sorted.partitioner.get).toSortedRDD.collect.toMap
      def newMV = sorted.mapValuesWithKeys({ case (id, x) => (id, x) }).collect.toMap
    }
    val parts = 4
    val table = "%10s | %10s | %10s"
    println(table.format("rows", "old (ms)", "new (ms)"))
    for (round <- 10 to 20) {
      val rows = 10000 * round
      val demo = new Demo(parts, rows)
      val mew = Timed(demo.newMV)
      val old = Timed(demo.oldMV)
      println(table.format(parts * rows, old.nanos / 1000000, mew.nanos / 1000000))
      assert(mew.value == old.value)
    }
  }
}
