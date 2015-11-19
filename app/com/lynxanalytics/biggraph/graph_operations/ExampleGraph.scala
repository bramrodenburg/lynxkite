// A small graph with all sorts of attributes. Used for testing.
package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object ExampleGraph extends OpFromJson {
  class Input extends MagicInputSignature {
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val (vertices, edges) = graph
    val name = vertexAttribute[String](vertices)
    val age = vertexAttribute[Double](vertices)
    val gender = vertexAttribute[String](vertices)
    val income = vertexAttribute[Double](vertices) // Partially defined.
    val location = vertexAttribute[(Double, Double)](vertices)
    val comment = edgeAttribute[String](edges)
    val weight = edgeAttribute[Double](edges)
    val greeting = scalar[String]
    // For wholesale access.
    val scalars = Map("greeting" -> greeting)
    val edgeAttributes = Map("comment" -> comment, "weight" -> weight)
    val vertexAttributes = Map(
      "name" -> name,
      "age" -> age,
      "gender" -> gender,
      "income" -> income,
      "location" -> location)
  }
  def fromJson(j: JsValue) = ExampleGraph()
}
import ExampleGraph._
case class ExampleGraph() extends TypedMetaGraphOp[Input, Output] {
  @transient var executionCounter = 0

  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: ExampleGraph.Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    executionCounter += 1

    val sc = rc.sparkContext
    val partitioner = rc.onePartitionPartitioner
    output(
      o.vertices,
      sc.parallelize(Seq(0l, 1l, 2l, 3l).map((_, ())))
        .sortUnique(partitioner))
    output(
      o.edges,
      sc.parallelize(Seq(
        (0l, Edge(0l, 1l)),
        (1l, Edge(1l, 0l)),
        (2l, Edge(2l, 0l)),
        (3l, Edge(2l, 1l))))
        .sortUnique(partitioner))
    output(o.name, sc.parallelize(Seq(
      (0l, "Adam"),
      (1l, "Eve"),
      (2l, "Bob"),
      (3l, "Isolated Joe"))).sortUnique(partitioner))
    output(o.age, sc.parallelize(Seq(
      (0l, 20.3),
      (1l, 18.2),
      (2l, 50.3),
      (3l, 2.0))).sortUnique(partitioner))
    output(o.gender, sc.parallelize(Seq(
      (0l, "Male"),
      (1l, "Female"),
      (2l, "Male"),
      (3l, "Male"))).sortUnique(partitioner))
    output(o.income, sc.parallelize(Seq(
      (0l, 1000.0),
      (2l, 2000.0))).sortUnique(partitioner))
    output(o.location, sc.parallelize(Seq(
      (0l, (40.71448, -74.00598)), // New York
      (1l, (47.5269674, 19.0323968)), // Budapest
      (2l, (1.352083, 103.819836)), // Singapore
      (3l, (-33.8674869, 151.2069902)) // Sydney
    )).sortUnique(partitioner))
    output(o.comment, sc.parallelize(Seq(
      (0l, "Adam loves Eve"),
      (1l, "Eve loves Adam"),
      (2l, "Bob envies Adam"),
      (3l, "Bob loves Eve"))).sortUnique(partitioner))
    output(o.weight, sc.parallelize(Seq(
      (0l, 1.0),
      (1l, 2.0),
      (2l, 3.0),
      (3l, 4.0))).sortUnique(partitioner))
    output(o.greeting, "Hello world!")
  }
}
