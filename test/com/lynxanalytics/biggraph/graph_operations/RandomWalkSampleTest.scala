package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import org.scalatest.FunSuite

class RandomWalkSampleTest extends FunSuite with TestGraphOp {
  val g = SmallTestGraph(Map(
    0 -> Seq(1),
    1 -> Seq(2),
    2 -> Seq(3),
    3 -> Seq(4),
    4 -> Seq(5, 6, 7, 8, 9, 10),
    5 -> Seq(0),
    6 -> Seq(0),
    7 -> Seq(0),
    8 -> Seq(0),
    9 -> Seq(0),
    10 -> Seq(0)
  )).result

  test("too large sample size") {
    val op = RandomWalkSample(0.1, g.vs.rdd.count() + 10, 0)
    val output = op(op.vs, g.vs)(op.es, g.es).result
    val nodesNotInSample = output.verticesInSample.rdd.filter(_._2 == 0.0)
    val edgesNotInSample = output.edgesInSample.rdd.filter(_._2 == 0.0)
    assert(nodesNotInSample.count() == 0)
    assert(edgesNotInSample.count() == 0)
  }

  test("one node sample") {
    val op = RandomWalkSample(0.1, 1, 0)
    val output = op(op.vs, g.vs)(op.es, g.es).result
    assert(output.verticesInSample.rdd.filter(_._2 > 0.0).count() == 1)
    assert(output.edgesInSample.rdd.filter(_._2 > 0.0).count() == 0)
  }

  test("two nodes sample") {
    val op = RandomWalkSample(0.1, 2, 0)
    val output = op(op.vs, g.vs)(op.es, g.es).result
    assert(output.verticesInSample.rdd.filter(_._2 > 0.0).count() == 2)
    assert(output.edgesInSample.rdd.filter(_._2 > 0.0).count() == 1)
  }

  test("seven nodes sample") {
    // the only walk with seven unique nodes (supposing restartProbability ~= 0.0) is
    // [0, 1, 2, 3, 4, x] * k + [0, 1, 2, 3, 4, y] for k > 0
    val op = RandomWalkSample(0.000000000001, 7, 0)
    val output = op(op.vs, g.vs)(op.es, g.es).result
    assert(output.verticesInSample.rdd.filter(_._1 > 4).filter(_._2 > 0.0).count() == 2)
  }

  test("unconnected graph") {
    val unconnectedG = SmallTestGraph(Map(
      0 -> Seq(1),
      1 -> Seq(0),
      2 -> Seq(3),
      3 -> Seq(2)
    )).result
    val op = RandomWalkSample(0.1, 3, 0)
    val output = op(op.vs, unconnectedG.vs)(op.es, unconnectedG.es).result
    assert(output.verticesInSample.rdd.filter(_._2 > 0.0).count() == 3)
    assert(output.edgesInSample.rdd.filter(_._2 > 0.0).count() == 2)
  }
}
