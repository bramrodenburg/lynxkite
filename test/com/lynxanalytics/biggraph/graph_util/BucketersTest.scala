package com.lynxanalytics.biggraph.graph_util

import org.scalatest.FunSuite

class VertexBucketerTest extends FunSuite {
  test("Long bucketer works as expected") {
    assert((1 to 6).flatMap(LongBucketer(1, 6, 3).whichBucket(_)) ==
      Seq(0, 0, 1, 1, 2, 2))
    assert((1 to 7).flatMap(LongBucketer(1, 7, 3).whichBucket(_)) ==
      Seq(0, 0, 0, 1, 1, 1, 2))
    assert((1 to 8).flatMap(LongBucketer(1, 8, 3).whichBucket(_)) ==
      Seq(0, 0, 0, 1, 1, 1, 2, 2))
    assert((1 to 9).flatMap(LongBucketer(1, 9, 3).whichBucket(_)) ==
      Seq(0, 0, 0, 1, 1, 1, 2, 2, 2))
    assert(new LongBucketer(1, 6, 3).bounds == Seq(3, 5))
  }

  test("Double linear bucketer works as expected") {
    var fb = DoubleLinearBucketer(1, 6, 3)
    assert(fb.bounds == Seq(1 + 5.0 / 3, 1 + 2 * 5.0 / 3))
    assert(fb.whichBucket(1.00).get == 0)
    assert(fb.whichBucket(2.66).get == 0)
    assert(fb.whichBucket(2.67).get == 1)
    assert(fb.whichBucket(4.33).get == 1)
    assert(fb.whichBucket(4.34).get == 2)
    assert(fb.whichBucket(6.00).get == 2)
    fb = DoubleLinearBucketer(0.2, 0.9, 7)
    assert(fb.bounds == Seq(0.3, 0.4, 0.5, 0.6, 0.7, 0.8))
  }

  test("Double logarithmic bucketer works as expected") {
    var fb = DoubleLogBucketer(1, 1000, 3)
    // This is not exactly true, thanks to inaccuracies in the arithmetic.
    // assert(fb.bounds == Seq(10, 100))
    // But when rounded for string formatting, we get the expected result.
    assert(fb.bucketLabels == Seq("1", "10", "100", "1000"))
    // And numbers generally end up in the right bucket.
    assert(fb.whichBucket(0).get == 0)
    assert(fb.whichBucket(1).get == 0)
    assert(fb.whichBucket(9).get == 0)
    assert(fb.whichBucket(10).get == 1)
    assert(fb.whichBucket(99).get == 1)
    assert(fb.whichBucket(100).get == 2)
    assert(fb.whichBucket(1000).get == 2)
  }

  test("Unexpected strings are ignored") {
    val b = StringBucketer(Seq("adam", "eve"), hasOther = false)
    assert(b.whichBucket("adam").get == 0)
    assert(b.whichBucket("eve").get == 1)
    assert(b.whichBucket("george") == None)
  }

  test("Bucketing numeric labels are wonderful") {
    assert(LongBucketer(1, 8, 3).bucketLabels == Seq("1", "4", "7", "8"))
  }

  test("Bucketing double labels for large numbers") {
    assert(DoubleLinearBucketer(100, 400, 3).bucketLabels == Seq("100", "200", "300", "400"))
  }
  test("Bucketing double labels for small numbers") {
    assert(DoubleLinearBucketer(0.001, 0.002, 2).bucketLabels == Seq("0.0010", "0.0015", "0.0020"))
  }
  test("Bucketing double labels for small differences") {
    assert(DoubleLinearBucketer(3.001, 3.004, 3).bucketLabels == Seq("3.001", "3.002", "3.003", "3.004"))
  }

  test("Bucketing long labels by integer division") {
    assert(LongBucketer(0, 4, 5).bucketLabels == Seq("0", "1", "2", "3", "4"))
  }

  test("Bucketing string labels are wonderful too") {
    assert(StringBucketer(Seq("adam", "eve", "george"), hasOther = true).bucketLabels
      == Seq("adam", "eve", "george", "Other"))
  }

  test("String bucket filters") {
    assert(StringBucketer(Seq("adam", "eve"), hasOther = false).bucketFilters
      == Seq("adam", "eve"))
    assert(StringBucketer(Seq("adam", "eve"), hasOther = true).bucketFilters
      == Seq("adam", "eve", "!adam,eve"))
  }

  test("Double bucket filters") {
    assert(DoubleLinearBucketer(10, 20, 1).bucketFilters
      == Seq())
    assert(DoubleLinearBucketer(10, 30, 2).bucketFilters
      == Seq("<20", ">=20"))
    assert(DoubleLinearBucketer(10, 40, 3).bucketFilters
      == Seq("<20", "[20,30)", ">=30"))
    assert(DoubleLogBucketer(1, 10000, 4).bucketFilters
      == Seq("<10", "[10,100)", "[100,1000)", ">=1000"))
  }
}
