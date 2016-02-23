package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.graph_api.Scripting._

class DerivedAttributeOperationTest extends OperationsTestBase {
  test("Derived vertex attribute (Double)") {
    run("Example Graph")
    run("Derived vertex attribute",
      Map("type" -> "double", "output" -> "output", "expr" -> "100 + age + 10 * name.length"))
    val attr = project.vertexAttributes("output").runtimeSafeCast[Double]
    assert(attr.rdd.collect.toMap == Map(0 -> 160.3, 1 -> 148.2, 2 -> 180.3, 3 -> 222.0))
  }

  test("Multi-line function") {
    run("Example Graph")
    run("Derived vertex attribute",
      Map("type" -> "double", "output" -> "output", "expr" -> """
        (function() {
          return age;
        })()"""))
    val attr = project.vertexAttributes("output").runtimeSafeCast[Double]
    assert(attr.rdd.collect.toMap == Map(0 -> 20.3, 1 -> 18.2, 2 -> 50.3, 3 -> 2.0))
  }

  test("Multi-line expression and utility function") {
    run("Example Graph")
    run("Derived vertex attribute",
      Map("type" -> "double", "output" -> "output", "expr" -> """
        var rnd = util.rnd(income);
        rnd.nextDouble() + rnd.nextDouble();"""))
    val attr = project.vertexAttributes("output").runtimeSafeCast[Double]
    def rndSumScala(income: Double) = {
      val rnd = new scala.util.Random(income.toLong)
      rnd.nextDouble + rnd.nextDouble
    }
    assert(attr.rdd.collect.toMap == Map(0 -> rndSumScala(1000.0), 2 -> rndSumScala(2000.0)))
  }

  test("Vector attribute") {
    run("Example Graph")
    run("Aggregate on neighbors",
      Map("prefix" -> "neighbor", "direction" -> "all edges", "aggregate-name" -> "vector"))
    run("Derived vertex attribute",
      Map("type" -> "string", "output" -> "output", "expr" -> """
        (function() { neighbor_name_vector.sort(); return neighbor_name_vector[0]; })()"""))
    val attr = project.vertexAttributes("output").runtimeSafeCast[String]
    assert(attr.rdd.collect.toMap == Map(0 -> "Bob", 1 -> "Adam", 2 -> "Adam"))
  }

  test("Primitive vector attribute") {
    run("Example Graph")
    run("Aggregate on neighbors",
      Map("prefix" -> "neighbor", "direction" -> "all edges", "aggregate-age" -> "vector"))
    run("Derived vertex attribute",
      Map("type" -> "double", "output" -> "output", "expr" -> """
        (function() {
           if (neighbor_age_vector.length > 0) {
             neighbor_age_vector.sort();
             return neighbor_age_vector[0] * 1;
           }
           return undefined;
         })()"""))
    val attr = project.vertexAttributes("output").runtimeSafeCast[Double]
    assert(attr.rdd.collect.toMap == Map(0 -> 18.2, 1 -> 20.3, 2 -> 18.2))
  }

  test("Vector of vector attribute") {
    run("Example Graph")
    run("Aggregate on neighbors",
      Map("prefix" -> "neighbor", "direction" -> "all edges", "aggregate-age" -> "vector"))
    run("Aggregate on neighbors",
      Map(
        "prefix" -> "neighbor",
        "direction" -> "all edges",
        "aggregate-neighbor_age_vector" -> "vector"))
    run("Derived vertex attribute",
      Map("type" -> "double", "output" -> "output", "expr" -> """
        neighbor_neighbor_age_vector_vector.map(function(subarray) {
          return subarray.reduce(function(a, b) { return a + b; }, 0);
        }).reduce(function(a, b) { return a + b; }, 0)"""))
    val attr = project.vertexAttributes("output").runtimeSafeCast[Double]
    assert(attr.rdd.collect.toMap == Map(0 -> 220.3, 1 -> 211.89999999999998, 2 -> 177.6))
  }

  test("Vector length") {
    run("Example Graph")
    run("Aggregate on neighbors",
      Map("prefix" -> "neighbor", "direction" -> "all edges", "aggregate-name" -> "vector"))
    run("Derived vertex attribute",
      Map("type" -> "double", "output" -> "output", "expr" -> "neighbor_name_vector.length"))
    val attr = project.vertexAttributes("output").runtimeSafeCast[Double]
    assert(attr.rdd.collect.toMap == Map(0 -> 3, 1 -> 3, 2 -> 2))
  }

  test("Wrong type") {
    val e = intercept[org.apache.spark.SparkException] {
      run("Derived vertex attribute",
        Map("type" -> "double", "output" -> "output", "expr" -> "'hello'"))
      project.vertexAttributes("output").runtimeSafeCast[Double].rdd.collect
    }
    assert(e.getCause.getMessage ==
      "assertion failed: JavaScript('hello') with values: {} did not return a valid number: NaN")
  }

  test("Derived vertex attribute with substring conflict (#1676)") {
    run("Example Graph")
    run("Rename vertex attribute", Map("from" -> "income", "to" -> "nam"))
    run("Derived vertex attribute",
      Map("type" -> "double", "output" -> "output", "expr" -> "100 + age + 10 * name.length"))
    val attr = project.vertexAttributes("output").runtimeSafeCast[Double]
    assert(attr.rdd.collect.size == 4)
  }

  test("Derived vertex attribute (String)") {
    run("Example Graph")
    // Test dropping values.
    run("Derived vertex attribute",
      Map("type" -> "string", "output" -> "gender",
        "expr" -> "name == 'Isolated Joe' ? undefined : gender"))
    run("Derived vertex attribute",
      Map("type" -> "string", "output" -> "output",
        "expr" -> "gender == 'Male' ? 'Mr ' + name : 'Ms ' + name"))
    val attr = project.vertexAttributes("output").runtimeSafeCast[String]
    assert(attr.rdd.collect.toMap == Map(0 -> "Mr Adam", 1 -> "Ms Eve", 2 -> "Mr Bob"))
  }

  // TODO: Re-enable this test. See #1037.
  ignore("Derived edge attribute") {
    run("Example Graph")
    // Test dropping values.
    run("Derived edge attribute",
      Map("type" -> "string", "output" -> "tripletke",
        "expr" -> "src$name + ':' + comment + ':' + dst$age + '#' + weight"))
    val attr = project.edgeAttributes("tripletke").runtimeSafeCast[String]
    assert(attr.rdd.collect.toSeq == Seq(
      (0, "Adam:Adam loves Eve:18.2#1"),
      (1, "Eve:Eve loves Adam:20.3#2"),
      (2, "Bob:Bob envies Adam:20.3#3"),
      (3, "Bob:Bob loves Eve:18.2#4")))
  }
}
