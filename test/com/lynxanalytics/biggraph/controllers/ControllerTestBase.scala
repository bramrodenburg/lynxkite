package com.lynxanalytics.biggraph.controllers

import scala.reflect.runtime.universe.TypeTag
import scala.reflect.ClassTag
import org.scalatest.{ FunSuite, BeforeAndAfterEach }

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._

class ControllerTestBase extends FunSuite with TestGraphOp with BeforeAndAfterEach {
  val controller = new BigGraphController(this)
  val projectName = "Test_Project"
  def projectFrame = ProjectFrame.fromName(projectName)
  def subProject = projectFrame.subproject
  val user = com.lynxanalytics.biggraph.serving.User.fake

  def run(op: String, params: Map[String, String] = Map(), on: String = projectName) =
    controller.projectOp(
      user,
      ProjectOperationRequest(on, FEOperationSpec(Operation.titleToID(op), params)))

  def vattr[T: TypeTag: ClassTag: Ordering](name: String) = {
    val attr = subProject.viewer.vertexAttributes(name).runtimeSafeCast[T]
    attr.rdd.values.collect.toSeq.sorted
  }

  def eattr[T: TypeTag: ClassTag: Ordering](name: String) = {
    val attr = subProject.viewer.edgeAttributes(name).runtimeSafeCast[T]
    attr.rdd.values.collect.toSeq.sorted
  }

  override def beforeEach() = {
    val path = SymbolPath("projects")
    if (metaGraphManager.tagExists(path)) {
      for (t <- metaGraphManager.lsTag(path)) {
        metaGraphManager.rmTag(t)
      }
    }
    controller.createProject(
      user,
      CreateProjectRequest(name = projectName, notes = "test project", privacy = "private"))
  }
}
