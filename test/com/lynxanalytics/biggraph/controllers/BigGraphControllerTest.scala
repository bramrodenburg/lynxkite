package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.graph_api.Scripting._

class BigGraphControllerTest extends BigGraphControllerTestBase {
  test("filtering by vertex attribute") {
    run("Example Graph")
    val filter = ProjectAttributeFilter("age", "<40")
    controller.filterProject(user, ProjectFilterRequest(projectName, List(filter), List()))
    assert(vattr[String]("name") == Seq("Adam", "Eve", "Isolated Joe"))
    assert(eattr[String]("comment") == Seq("Adam loves Eve", "Eve loves Adam"))
    assert(subProject.toFE.undoOp == "Filter age <40")
  }

  test("filtering by vertex attribute (no edge bundle)") {
    run("Example Graph")
    run("Discard edges")
    val filter = ProjectAttributeFilter("age", "<40")
    controller.filterProject(user, ProjectFilterRequest(projectName, List(filter), List()))
    assert(vattr[String]("name") == Seq("Adam", "Eve", "Isolated Joe"))
    assert(subProject.toFE.undoOp == "Filter age <40")
  }

  test("filtering by partially defined vertex attribute") {
    run("Example Graph")
    val filter = ProjectAttributeFilter("income", ">1000")
    controller.filterProject(user, ProjectFilterRequest(projectName, List(filter), List()))
    assert(vattr[String]("name") == Seq("Bob"))
  }

  test("filtering by edge attribute") {
    run("Example Graph")
    val filter = ProjectAttributeFilter("weight", ">2")
    controller.filterProject(user, ProjectFilterRequest(projectName, List(), List(filter)))
    assert(vattr[String]("name") == Seq("Adam", "Bob", "Eve", "Isolated Joe"))
    assert(eattr[String]("comment") == Seq("Bob envies Adam", "Bob loves Eve"))
    assert(subProject.toFE.undoOp == "Filter weight >2")
  }

  def list(dir: String) = controller.projectList(user, ProjectListRequest(dir))

  test("project list") {
    val pl = list("")
    assert(pl.objects.size == 1)
    assert(pl.objects(0).name == "Test_Project")
    assert(pl.objects(0).vertexCount.isEmpty)
    assert(pl.objects(0).edgeCount.isEmpty)
  }

  test("project list with scalars") {
    run("Example Graph")
    controller.forkEntry(user, ForkEntryRequest(from = projectName, to = "new_project"))
    val pl = list("")
    assert(pl.objects.size == 2)
    assert(pl.objects(1).name == "new_project")
    assert(!pl.objects(1).vertexCount.isEmpty)
    assert(!pl.objects(1).edgeCount.isEmpty)
  }

  test("fork project") {
    run("Example Graph")
    controller.forkEntry(user, ForkEntryRequest(from = projectName, to = "forked"))
    assert(list("").objects.size == 2)
  }

  test("create directory") {
    controller.createDirectory(user, CreateDirectoryRequest(
      name = "foo/bar", privacy = "private"))
    assert(list("").directories == Seq("foo"))
    assert(list("foo").objects.isEmpty)
    assert(list("foo").directories == Seq("foo/bar"))
    controller.discardEntry(user, DiscardEntryRequest(name = "foo"))
    assert(list("").directories.isEmpty)
  }

  test("create directory inside project") {
    run("Example Graph")
    intercept[AssertionError] {
      controller.createDirectory(user, CreateDirectoryRequest(
        name = projectName + "/bar", privacy = "private"))
    }
  }
}
