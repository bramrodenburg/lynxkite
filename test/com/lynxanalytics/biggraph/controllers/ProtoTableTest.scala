package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.graph_api.Attribute
import com.lynxanalytics.biggraph.graph_operations.ExecuteSQL

class ProtoTableTest extends BigGraphControllerTestBase {
  private def attr(name: String): (String, Attribute[_]) =
    (name, Attribute[String](null, Symbol(name), null))
  val one = ProtoTable(null, Seq(attr("a"), attr("b"), attr("c"), attr("d")))
  val two = ProtoTable(null, Seq(attr("a"), attr("b"), attr("c"), attr("d")))
  val protoTables = Map("one" -> one, "two" -> two)

  private def compareProto(expected: Map[String, Set[String]], actual: Map[String, ProtoTable]) = {
    assert(expected.size == actual.size)
    for ((table, attributes) <- expected) {
      assert(attributes == actual(table).schema.map(_.name).toSet)
    }
  }

  Seq(
    ("select a from one", Map("one" -> Set("a"))),
    ("select * from one", Map("one" -> Set("a", "b", "c", "d"))),
    ("select a from one where b < 3", Map("one" -> Set("a", "b"))),
    ("select o.a, t.c from one o cross join two t",
      Map("one" -> Set("a"), "two" -> Set("c"))),
    ("select o.a, t.c from one o cross join one t",
      Map("one" -> Set("a"), "one" -> Set("c"))),
    ("select a from (select * from one)", Map("one" -> Set("a"))),
    ("select a from (select * from one) where b=11", Map("one" -> Set("a", "b"))),
    ("select o.a, two.c from one o inner join two on o.b=two.b where o.a=1",
      Map("one" -> Set("a", "b"), "two" -> Set("c", "b")))
  ).foreach {
      case (query, expected) =>
        test(query) {
          val plan = ExecuteSQL.getLogicalPlan(query, protoTables)
          val minimizedTables = ProtoTable.minimize(plan, protoTables)
          compareProto(expected, minimizedTables)
        }
    }
}
