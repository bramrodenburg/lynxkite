package com.lynxanalytics.biggraph.controllers

import org.scalatest.FunSuite

import play.api.libs.json
import com.lynxanalytics.biggraph._

class WorkspaceTest extends FunSuite with graph_api.TestGraphOp {
  val controller = new WorkspaceController(this)
  val bigGraphController = new BigGraphController(this)
  val ops = new frontend_operations.Operations(this)
  val user = serving.User.fake

  def create(name: String) =
    controller.createWorkspace(user, CreateWorkspaceRequest(name, "private"))
  def get(name: String): Workspace =
    controller.getWorkspace(user, GetWorkspaceRequest(name))
  def set(name: String, workspace: Workspace): Unit =
    controller.setWorkspace(user, SetWorkspaceRequest(name, workspace))
  def discard(name: String) =
    bigGraphController.discardEntry(user, DiscardEntryRequest(name))
  def using[T](name: String)(f: => T): T = {
    create(name)
    try {
      f
    } finally discard(name)
  }
  def getOpMeta(ws: String, box: String) =
    controller.getOperationMeta(user, GetOperationMetaRequest(ws, box))
  def getOutput(ws: String, box: String, output: String) =
    controller.getOutput(user, GetOutputRequest(ws, BoxOutput(box, output)))
  import WorkspaceJsonFormatters._
  import CheckpointRepository._
  def print[T: json.Writes](t: T): Unit = {
    println(json.Json.prettyPrint(json.Json.toJson(t)))
  }

  val pagerankParams = Map(
    "name" -> "pagerank", "damping" -> "0.85", "weights" -> "!no weight",
    "iterations" -> "5", "direction" -> "all edges")

  test("pagerank on example graph") {
    val eg = Box("eg", "Create example graph", Map(), 0, 0, Map())
    val pr = Box("pr", "Compute PageRank", pagerankParams, 0, 20, Map("project" -> eg.output("project")))
    val ws = Workspace(List(eg, pr))
    val project = ws.state(user, ops, pr.output("project")).project
    import graph_api.Scripting._
    assert(project.vertexAttributes("pagerank").rdd.values.collect.toSet == Set(
      1.4099834026132592, 1.4099834026132592, 0.9892062327983842, 0.19082696197509774))
  }

  test("deltas still work") {
    val eg = Box("eg", "Create example graph", Map(), 0, 0, Map())
    val merge = Box(
      "merge", "Merge vertices by attribute", Map("key" -> "gender"), 0, 20,
      Map("project" -> eg.output("project")))
    val ws = Workspace(List(eg, merge))
    val project = ws.state(user, ops, merge.output("project")).project
    import graph_api.Scripting._
    assert(project.scalars("!vertex_count_delta").value == -2)
  }

  test("validation") {
    val eg = Box("eg", "Create example graph", Map(), 0, 0, Map())
    val ex = intercept[AssertionError] {
      Workspace(List(eg, eg))
    }
    assert(ex.getMessage.contains("Duplicate box name: eg"))
  }

  test("errors") {
    val pr1 = Box("pr1", "Compute PageRank", pagerankParams, 0, 20, Map())
    val pr2 = pr1.copy(id = "pr2", inputs = Map("project" -> pr1.output("project")))
    val ws = Workspace(List(pr1, pr2))
    val p1 = ws.state(user, ops, pr1.output("project"))
    val p2 = ws.state(user, ops, pr2.output("project"))
    val ex1 = intercept[AssertionError] { p1.project }
    val ex2 = intercept[AssertionError] { p2.project }
    assert(ex1.getMessage.contains("Input project is not connected."))
    assert(ex2.getMessage.contains("Input project has an error."))
  }

  test("getProject") {
    using("test-workspace") {
      assert(get("test-workspace").boxes.isEmpty)
      val eg = Box("eg", "Create example graph", Map(), 0, 0, Map())
      val ws = Workspace(List(eg))
      set("test-workspace", ws)
      val o = getOutput("test-workspace", "eg", "project")
      assert(o.kind == "project")
      val income = o.project.get.vertexAttributes.find(_.title == "income").get
      assert(income.metadata("icon") == "money_bag")
    }
  }

  test("getOperationMeta") {
    using("test-workspace") {
      assert(get("test-workspace").boxes.isEmpty)
      val eg = Box("eg", "Create example graph", Map(), 0, 0, Map())
      val cc = Box(
        "cc", "Find connected components", Map("name" -> "cc", "directions" -> "ignore directions"),
        0, 20, Map())
      val pr = Box("pr", "Compute PageRank", pagerankParams, 0, 20, Map())
      set("test-workspace", Workspace(List(eg, cc, pr)))
      intercept[AssertionError] {
        getOpMeta("test-workspace", "pr")
      }
      set(
        "test-workspace",
        Workspace(List(
          eg,
          cc.copy(inputs = Map("project" -> eg.output("project"))),
          pr.copy(inputs = Map("project" -> cc.output("project"))))))
      val op = getOpMeta("test-workspace", "pr")
      assert(
        op.parameters.map(_.id) ==
          Seq("apply_to_project", "name", "weights", "iterations", "damping", "direction"))
      assert(
        op.parameters.find(_.id == "weights").get.options.map(_.id) == Seq("!no weight", "weight"))
      assert(
        op.parameters.find(_.id == "apply_to_project").get.options.map(_.id) == Seq("", "|cc"))
    }
  }

  test("2-input operation") {
    using("test-workspace") {
      assert(get("test-workspace").boxes.isEmpty)
      val eg = Box("eg", "Create example graph", Map(), 0, 0, Map())
      val blanks = Box("blanks", "Create vertices", Map("size" -> "2"), 0, 0, Map())
      val convert = Box(
        "convert", "Convert vertex attribute to double",
        Map("attr" -> "ordinal"), 0, 0, Map("project" -> blanks.output("project")))
      val srcs = Box(
        "srcs", "Derive vertex attribute",
        Map("output" -> "src", "type" -> "string", "expr" -> "ordinal == 0 ? 'Adam' : 'Eve'"),
        0, 0, Map("project" -> convert.output("project")))
      val dsts = Box(
        "dsts", "Derive vertex attribute",
        Map("output" -> "dst", "type" -> "string", "expr" -> "ordinal == 0 ? 'Eve' : 'Bob'"),
        0, 0, Map("project" -> srcs.output("project")))
      val combine = Box(
        "combine", "Import edges for existing vertices",
        Map("attr" -> "name", "src" -> "src", "dst" -> "dst"), 0, 0,
        Map("project" -> eg.output("project"), "edges" -> dsts.output("project")))
      val ws = Workspace(List(eg, blanks, convert, srcs, dsts, combine))
      set("test-workspace", ws)
      val op = getOpMeta("test-workspace", "combine")
      assert(
        op.parameters.find(_.id == "attr").get.options.map(_.id) ==
          Seq("!unset", "age", "gender", "id", "income", "location", "name"))
      assert(
        op.parameters.find(_.id == "src").get.options.map(_.id) ==
          Seq("!unset", "dst", "id", "ordinal", "src"))
      val project = ws.state(user, ops, combine.output("project")).project
      import graph_api.Scripting._
      import graph_util.Scripting._
      assert(project.edgeBundle.countScalar.value == 2)
    }
  }

  test("progress success") {
    using("test-workspace") {
      assert(get("test-workspace").boxes.isEmpty)
      val eg = Box("eg", "Example Graph", Map(), 0, 0, Map())
      val cc = Box(
        "cc", "Connected components", Map("name" -> "cc", "directions" -> "ignore directions"),
        0, 20, Map("project" -> eg.output("project")))
      // use a different pagerankParams to prevent reusing the attribute computed in an earlier
      // test
      val pr = Box("pr", "PageRank", pagerankParams + ("iterations" -> "3"), 0, 20,
        Map("project" -> cc.output("project")))
      val prOutput = pr.output("project")
      val ws = Workspace(List(eg, cc, pr))
      set("test-workspace", ws)
      val progressBeforePR = controller.getProgress(user,
        GetProgressRequest("test-workspace", prOutput)
      ).progressList.find(_.boxOutput == prOutput).get
      assert(progressBeforePR.success.enabled)
      assert(progressBeforePR.progress("inProgress") == 0)
      val computedBeforePR = progressBeforePR.progress("computed")
      import graph_api.Scripting._
      // trigger PR computation
      ws.state(user, ops, pr.output("project")).project.vertexAttributes(pagerankParams("name")).
        rdd.values.collect
      val progressAfterPR = controller.getProgress(user,
        GetProgressRequest("test-workspace", prOutput)
      ).progressList.find(_.boxOutput == prOutput).get
      val computedAfterPR = progressAfterPR.progress("computed")
      assert(computedAfterPR > computedBeforePR)
    }
  }

  test("progress fails") {
    using("test-workspace") {
      assert(get("test-workspace").boxes.isEmpty)
      // box with unconnected input
      val pr = Box("pr", "PageRank", pagerankParams, 0, 20, Map())
      val prOutput = pr.output("project")
      val ws = Workspace(List(pr))
      set("test-workspace", ws)
      val progress = controller.getProgress(user,
        GetProgressRequest("test-workspace", prOutput)
      ).progressList.find(_.boxOutput == prOutput).get
      assert(!progress.success.enabled)
    }
  }
}
