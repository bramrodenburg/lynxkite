package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark
import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.scalatest.FunSuite

import scala.util.Random
import scala.language.implicitConversions

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.GraphTestUtils._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.spark_util.Implicits._

class FingerprintingTest extends FunSuite with TestGraphOp {
  test("two easy pairs") {
    assert(fingerprint(
      Map(10 -> Seq(1, 2, 3), 11 -> Seq(4, 5, 6)),
      Map(20 -> Seq(1, 2, 3), 21 -> Seq(4, 5, 6)))
      == Seq(10 -> 20, 11 -> 21))
  }

  test("one difficult pair A") {
    assert(fingerprint(
      Map(10 -> Seq(1, 2, 3, 10, 11), 11 -> Seq(1, 2, 3, 4)),
      Map(20 -> Seq(1, 2, 3, 4)))
      == Seq(11 -> 20))
  }

  test("one difficult pair B") {
    assert(fingerprint(
      Map(10 -> Seq(1, 2, 3, 4)),
      Map(20 -> Seq(1, 2, 3, 20, 21), 21 -> Seq(1, 2, 3, 4)))
      == Seq(10 -> 21))
  }

  test("no match") {
    assert(fingerprint(
      Map(10 -> Seq(1, 2, 3)),
      Map(20 -> Seq(4, 5, 6)))
      == Seq())
  }

  def fingerprint(left: Map[Int, Seq[Int]], right: Map[Int, Seq[Int]]): Seq[(Int, Int)] = {
    val graph = SmallTestGraph(left ++ right).result
    val weights = AddConstantAttribute.run(graph.es.asVertexSet, 1.0)
    val candidates = {
      val op = AddEdgeBundle(for { l <- left.keys.toSeq; r <- right.keys.toSeq } yield l -> r)
      op(op.vsA, graph.vs)(op.vsB, graph.vs).result.esAB
    }
    val fingerprinting = {
      val op = Fingerprinting(1, 0)
      op(
        op.srcEdges, graph.es)(
          op.dstEdges, graph.es)(
            op.srcEdgeWeights, weights)(
              op.dstEdgeWeights, weights)(
                op.candidates, candidates).result
    }
    fingerprinting.matching.toPairSeq.map { case (l, r) => (l.toInt, r.toInt) }
  }
}

class FingerprintingCandidatesTest extends FunSuite with TestGraphOp {
  test("two pairs") {
    assert(candidates(
      Map(10 -> Seq(1, 2, 3), 11 -> Seq(4, 5, 6)),
      Map(20 -> Seq(1, 2, 3), 21 -> Seq(4, 5, 6)))
      == Seq(10 -> 20, 11 -> 21))
  }

  test("two left, one right") {
    assert(candidates(
      Map(10 -> Seq(1, 2, 3), 11 -> Seq(1, 2, 3, 4)),
      Map(20 -> Seq(1, 2, 3, 4)))
      == Seq(10 -> 20, 11 -> 20))
  }

  test("one left, two right") {
    assert(candidates(
      Map(10 -> Seq(1, 2, 3, 4)),
      Map(20 -> Seq(1, 2, 3), 21 -> Seq(1, 2, 3, 4)))
      == Seq(10 -> 20, 10 -> 21))
  }

  test("two left, two right") {
    assert(candidates(
      Map(10 -> Seq(1, 2, 3), 11 -> Seq(1, 2, 3, 4)),
      Map(20 -> Seq(1, 2, 3), 21 -> Seq(1, 2, 3, 4)))
      == Seq(10 -> 20, 10 -> 21, 11 -> 20, 11 -> 21))
  }

  test("no match") {
    assert(candidates(
      Map(10 -> Seq(1, 2, 3)),
      Map(20 -> Seq(4, 5, 6)))
      == Seq())
  }

  def candidates(left: Map[Int, Seq[Int]], right: Map[Int, Seq[Int]]): Seq[(Int, Int)] = {
    val graph = SmallTestGraph(left ++ right).result
    val leftName = {
      val op = AddVertexAttribute((left.keys ++ left.values.flatten).map(i => i -> s"L$i").toMap)
      op(op.vs, graph.vs).result.attr
    }
    val rightName = {
      val op = AddVertexAttribute((right.keys ++ right.values.flatten).map(i => i -> s"R$i").toMap)
      op(op.vs, graph.vs).result.attr
    }
    val candidates = {
      val op = FingerprintingCandidates()
      op(op.es, graph.es)(op.leftName, leftName)(op.rightName, rightName).result.candidates
    }
    candidates.toPairSeq.map { case (l, r) => (l.toInt, r.toInt) }
  }
}
