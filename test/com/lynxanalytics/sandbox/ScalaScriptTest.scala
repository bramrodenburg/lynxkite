package com.lynxanalytics.sandbox

import org.scalatest.FunSuite
import java.security.AccessControlException

class ScalaScriptTest extends FunSuite {

  def worksUnlessRestricted(code: String): String = {
    intercept[AccessControlException] {
      ScalaScript.run(code, restricted = true)
    }
    val result = ScalaScript.run(code, restricted = false)
    result
  }

  def worksEvenAsRestricted(code: String): String = {
    val result = ScalaScript.run(code, restricted = false)
    ScalaScript.run(code, restricted = true)
    // The results may not be identical, but we won't check this
    result
  }

  test("Can't do infinite loop, even when non-restricted") {
    val code =
      """
        Thread.sleep(15000L)
      """
    intercept[java.util.concurrent.TimeoutException] {
      ScalaScript.run(code, restricted = false)
    }
    intercept[java.util.concurrent.TimeoutException] {
      ScalaScript.run(code, restricted = true)
    }
  }

  test("Simple arithmetic works") {
    val code = "5 * 5 + 1"

    assert(worksEvenAsRestricted(code) == "26")
  }

  test("Security manager disables file access") {
    val testFile = getClass.getResource("/graph_api/permission_check.txt")
    val contents = "This file is used to check the security manager implementation.\n"
    assert(scala.io.Source.fromFile(testFile.getFile).mkString == contents)
    val path = testFile.getPath
    val code = s"""scala.io.Source.fromFile("${path}").mkString"""
    assert(worksUnlessRestricted(code) == contents)

  }

  test("Can't replace the security manager") {
    val code = "System.setSecurityManager(null)"
    worksUnlessRestricted(code)
  }

  // This fails, because we cannot create classes in restricted mode :(
  test("Can do some non-trivial, innocent computation") {
    val code =
      """
           class C {
             def compute(): String = {
                "Hello"
             }
           }
           val r = new C()
           r.compute()
      """
    val result = worksEvenAsRestricted(code)
    assert(result == "Hello")
  }

  // This passes, but not because we can't create a thread, but because
  // we cannot create classes. If we hack restricted mode so that
  // it allows class creation, this still passes, because we can
  // prevent spawning a new thread.
  test("Can't create a new thread") {
    val code =
      """
           class EvilRun extends Runnable {
             override def run(): Unit = {
             }
           }
             val r = new EvilRun()
             val t = new java.lang.Thread(r)
             t.start()

      """
    worksUnlessRestricted(code)
  }

  test("Can't access biggraph classes") {
    val code = "com.lynxanalytics.biggraph.graph_util.Timestamp.toString"
    val ts = com.lynxanalytics.biggraph.graph_util.Timestamp.toString
    val result = worksUnlessRestricted(code)
    assert(ts.toLong <= result.toLong)
  }
}
