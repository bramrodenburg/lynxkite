package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import scala.collection.mutable

object CheckClique {
  class Input extends MagicInputSignature {
    val vs = vertexSet
    val cliques = vertexSet
    val es = edgeBundle(vs, vs)
    val belongsTo = edgeBundle(vs, cliques)
  }
  class Output(implicit instance: MetaGraphOperationInstance, inputs: Input) extends MagicOutput(instance) {
    val dummy = scalar[Unit]
  }
}
import CheckClique._
case class CheckClique(cliquesToCheck: Option[Set[ID]] = None) extends TypedMetaGraphOp[Input, Output] {
  @transient override lazy val inputs = new Input

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas

    val vertexPartitioner = inputs.vs.rdd.partitioner.get
    val cliquePartitioner = inputs.cliques.rdd.partitioner.get
    val belongsTo = inputs.belongsTo.rdd
      .filter { case (_, edge) => cliquesToCheck.map(c => c.contains(edge.dst)).getOrElse(true) }
    val es = inputs.es.rdd

    val neighborsOut = es.map { case (_, edge) => edge.src -> edge.dst }
      .groupBySortedKey(vertexPartitioner)
    val neighborsIn = es.map { case (_, edge) => edge.dst -> edge.src }
      .groupBySortedKey(vertexPartitioner)
    val vsToCliques = belongsTo.map { case (_, edge) => edge.src -> edge.dst }
      .toSortedRDD(vertexPartitioner)

    val cliquesToVsWithNs = vsToCliques.sortedLeftOuterJoin(neighborsOut).sortedLeftOuterJoin(neighborsIn)
      .map { case (v, ((clique, nsOut), nsIn)) => clique -> (v, nsOut.getOrElse(Iterable()), nsIn.getOrElse(Iterable())) }
      .groupBySortedKey(cliquePartitioner)

    // for every node in the clique create outgoing and ingoing adjacency sets
    // put the node itself into these sets
    // create the intersection of all the sets, this should be the same as the clique members set
    val valid = cliquesToVsWithNs.foreach {
      case (clique, vsToNs) =>
        val members = vsToNs.map(_._1).toSet
        val outSets = mutable.Map[ID, mutable.Set[ID]](members.toSeq.map(m => m -> mutable.Set(m)): _*)
        val inSets = mutable.Map[ID, mutable.Set[ID]](members.toSeq.map(m => m -> mutable.Set(m)): _*)
        vsToNs.foreach {
          case (v, nsOut, nsIn) if members.contains(v) =>
            outSets(v) ++= nsOut
            inSets(v) ++= nsIn
        }
        assert((outSets.values.reduceLeft(_ & _) & inSets.values.reduceLeft(_ & _)) == members,
          s"clique $clique is not a maximal clique")
    }

    output(o.dummy, ())
  }
}
