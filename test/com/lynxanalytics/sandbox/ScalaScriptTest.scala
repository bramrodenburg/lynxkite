package com.lynxanalytics.sandbox

import org.scalatest.FunSuite
import java.security.AccessControlException

import com.lynxanalytics.biggraph.controllers.SQLController
import com.lynxanalytics.biggraph.graph_api.{ Scripting, Table, TestGraphOp }
import com.lynxanalytics.biggraph.graph_operations.ImportDataFrameTest
import com.lynxanalytics.biggraph.spark_util.SQLHelper
import org.apache.spark.sql.DataFrame

class ScalaScriptTest extends FunSuite with TestGraphOp {

  test("Can't do infinite loop") {
    val code =
      """
        Thread.sleep(3000L)
      """
    intercept[java.util.concurrent.TimeoutException] {
      ScalaScript.run(code, Map(), 2L)
    }
  }

  test("Simple arithmetic works") {
    val code = "5 * 5 + 1"

    assert(ScalaScript.run(code) == "26")
  }

  test("Bindings work") {
    assert(ScalaScript.run("s\"\"\"asd $qwe\"\"\"", Map("qwe" -> "123")) == "asd 123")
  }

  test("Scala DataFrame bindings work with runVegas") {
    val df = ImportDataFrameTest.jdbcDF(dataManager)
    val code = """Vegas("My plot test").
      withData(Data).
      encodeX("name", Nominal).
      encodeY("level", Quantitative).
      mark(Bar)"""
    val JSONString = ScalaScript.runVegas(code, df)
    assert(JSONString contains """"mark" : "bar"""")
    assert(JSONString contains "encoding")
    assert(JSONString contains "description")
    assert(JSONString contains "My plot test")
    assert(JSONString contains """"name" : "Felix",""")
  }

  test("Security manager disables file access") {
    val testFile = getClass.getResource("/graph_api/permission_check.txt")
    val contents = "This file is used to check the security manager implementation.\n"
    assert(scala.io.Source.fromFile(testFile.getFile).mkString == contents)
    val path = testFile.getPath
    val code = s"""scala.io.Source.fromFile("${path}").mkString"""
    intercept[AccessControlException] {
      ScalaScript.run(code)
    }
  }

  test("Can't replace the security manager") {
    val code = "System.setSecurityManager(null)"
    intercept[AccessControlException] {
      ScalaScript.run(code)
    }
  }

  test("Can do some non-trivial, innocent computation") {
    val code =
      """
           class C(val str: String) {
             def compute(): String = {
                str + "lo"
             }
           }
           val r = new C("hel")
           r.compute()
      """
    assert(ScalaScript.run(code) == "hello")
  }

  test("Can't create a new thread") {
    val code =
      """
           class EvilRun extends Runnable {
             override def run(): Unit = {
             // We could do anything here
             }
           }
             val r = new EvilRun()
             val t = new java.lang.Thread(r)
             t.start()

      """
    intercept[AccessControlException] {
      ScalaScript.run(code)
    }
  }

  test("Can't access biggraph classes") {
    val code = "com.lynxanalytics.biggraph.graph_util.Timestamp.toString"
    intercept[AccessControlException] {
      ScalaScript.run(code)
    }
  }

}
