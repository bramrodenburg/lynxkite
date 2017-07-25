package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.graph_api.Scripting._
import org.apache.spark

class GraphMetricsTest extends OperationsTestBase {

  private def toSeq(row: spark.sql.Row): Seq[Any] = {
    row.toSeq.map {
      case r: spark.sql.Row => toSeq(r)
      case x => x
    }
  }

  test("graph metrics works (Output: General Metrics)") {
    val gmbox = box("Create example graph")
      .box("built-ins/graph-metrics", Map(
        "CustomPercentile" -> "0",
        "MaximumNumberOfIterations" -> "30",
        "MinimalModularityIncerement" -> "0.001",
        "ModularClustering" -> "true")).output("General Metrics")


    val table = gmbox.table
    assert(table.schema.map(_.name) == Seq("Attribute", "Sum", "Avg", "Min", "5% percentile", "Med",
      "95% percentile", "Max", "0% (custom) percentile"))

    val data = table.df.collect.toSeq.map(row => toSeq(row))

    assert(data == Seq(Seq("Vertices", 4.0, null, null, null, null, null, null, null),
      Seq("Edges", 4.0, null, null, null, null, null, null, null),
      Seq("Degree", 8.0, 2.0, 0.0, 0.30000000000000004, 2.5, 3.0, 3.0, 0.0),
      Seq("In Degree", 4.0, 1.0, 0.0, 0.0, 1.0, 2.0, 2.0, 0.0),
      Seq("Out Degree", 4.0, 1.0, 0.0, 0.15000000000000002, 1.0, 1.8499999999999996, 2.0, 0.0),
      Seq("Sym Degree", 2.0, 0.5, 0.0, 0.0, 0.5, 1.0, 1.0, 0.0),
      Seq("Connected Components", 2.0, 2.0, 1.0, 1.1, 2.0, 2.8999999999999995, 3.0, 1.0),
      Seq("Modular Clusternig", 2.0, 2.0, 1.0, 1.1, 2.0, 2.8999999999999995, 3.0, 1.0)))

    val gmbox2 = box("Create example graph")
      .box("built-ins/graph-metrics", Map(
        "CustomPercentile" -> "0.7",
        "MaximumNumberOfIterations" -> "30",
        "MinimalModularityIncerement" -> "0.001",
        "ModularClustering" -> "true")).output("General Metrics")


    val table2 = gmbox2.table
    assert(table2.schema.map(_.name) == Seq("Attribute", "Sum", "Avg", "Min", "5% percentile", "Med", "95% percentile", "Max", "70% (custom) percentile"))



    val data2 = table2.df.collect.toSeq.map(row => toSeq(row))

    assert(data2 == Seq(Seq("Vertices", 4.0, null, null, null, null, null, null, null),
      Seq("Edges", 4.0, null, null, null, null, null, null, null),
      Seq("Degree", 8.0, 2.0, 0.0, 0.30000000000000004, 2.5, 3.0, 3.0, 3.0),
      Seq("In Degree", 4.0, 1.0, 0.0, 0.0, 1.0, 2.0, 2.0, 2.0),
      Seq("Out Degree", 4.0, 1.0, 0.0, 0.15000000000000002, 1.0, 1.8499999999999996, 2.0, 1.0999999999999996),
      Seq("Sym Degree", 2.0, 0.5, 0.0, 0.0, 0.5, 1.0, 1.0, 1.0),
      Seq("Connected Components", 2.0, 2.0, 1.0, 1.1, 2.0, 2.8999999999999995, 3.0, 2.3999999999999995),
      Seq("Modular Clusternig", 2.0, 2.0, 1.0, 1.1, 2.0, 2.8999999999999995, 3.0, 2.3999999999999995)))

  }

  test("graph metrics works (Output: Degree Distribution)") {
    val gmbox = box("Create example graph")
      .box("built-ins/graph-metrics", Map(
        "CustomPercentile" -> "0",
        "MaximumNumberOfIterations" -> "30",
        "MinimalModularityIncerement" -> "0.001",
        "ModularClustering" -> "true")).output("Degree Distribution")


    val plot = gmbox.plot.value
  }

  test("graph metrics works (Output: Component Distribution)") {
    val gmbox = box("Create example graph")
      .box("built-ins/graph-metrics", Map(
        "CustomPercentile" -> "0",
        "MaximumNumberOfIterations" -> "30",
        "MinimalModularityIncerement" -> "0.001",
        "ModularClustering" -> "true")).output("Component Distribution")


    val plot = gmbox.plot.value
  }

  test("graph metrics works (Output: Modular Distribution)") {
    val gmbox = box("Create example graph")
      .box("built-ins/graph-metrics", Map(
        "CustomPercentile" -> "0",
        "MaximumNumberOfIterations" -> "30",
        "MinimalModularityIncerement" -> "0.001",
        "ModularClustering" -> "true")).output("Modular Distribution")


    val plot = gmbox.plot.value
  }

}
