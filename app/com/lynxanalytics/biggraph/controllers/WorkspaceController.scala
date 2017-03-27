// Methods for manipulating workspaces.
package com.lynxanalytics.biggraph.controllers

import scala.collection.mutable.HashMap
import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.frontend_operations.Operations
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_util.Timestamp
import com.lynxanalytics.biggraph.serving
import play.api.libs.json

case class GetWorkspaceRequest(name: String)
case class BoxOutputIDPair(boxOutput: BoxOutput, id: String)
case class GetWorkspaceResponse(workspace: Workspace, outputs: List[BoxOutputIDPair])
case class SetWorkspaceRequest(name: String, workspace: Workspace)
case class GetOutputRequest(id: String)
case class GetProgressRequest(stateIDs: List[String])
case class GetOperationMetaRequest(workspace: String, box: String)
case class GetOutputResponse(kind: String, project: Option[FEProject] = None, success: FEStatus)
case class Progress(computed: Int, inProgress: Int, notYetStarted: Int, failed: Int)
case class GetProgressResponse(progressMap: Map[String, Progress])
case class CreateWorkspaceRequest(name: String, privacy: String)
case class BoxCatalogResponse(boxes: List[BoxMetadata])

class WorkspaceController(env: SparkFreeEnvironment) {
  implicit val metaManager = env.metaGraphManager
  implicit val entityProgressManager: EntityProgressManager = env.entityProgressManager

  val ops = new Operations(env)

  private def assertNameNotExists(name: String) = {
    assert(!DirectoryEntry.fromName(name).exists, s"Entry '$name' already exists.")
  }

  def createWorkspace(
    user: serving.User, request: CreateWorkspaceRequest): Unit = metaManager.synchronized {
    assertNameNotExists(request.name)
    val entry = DirectoryEntry.fromName(request.name)
    entry.assertParentWriteAllowedFrom(user)
    val w = entry.asNewWorkspaceFrame()
    w.setupACL(request.privacy, user)
  }

  private def getWorkspaceByName(
    user: serving.User, name: String): Workspace = metaManager.synchronized {
    val f = DirectoryEntry.fromName(name)
    assert(f.exists, s"Project ${name} does not exist.")
    f.assertReadAllowedFrom(user)
    f match {
      case f: WorkspaceFrame => f.workspace
      case _ => throw new AssertionError(s"${name} is not a workspace.")
    }
  }

  def getWorkspace(
    user: serving.User, request: GetWorkspaceRequest): GetWorkspaceResponse = {
    val workspace = getWorkspaceByName(user, request.name)
    val states = workspace.allStates(user, ops)
    val statesWithId = states.mapValues((_, Timestamp.toString)).view.force
    calculatedStates.synchronized {
      for ((_, (boxOutputState, id)) <- statesWithId) {
        calculatedStates(id) = boxOutputState
      }
    }
    val stateIDs = statesWithId.mapValues(_._2).toList.map((BoxOutputIDPair.apply _).tupled)
    GetWorkspaceResponse(workspace, stateIDs)
  }

  // This is for storing the calculated BoxOutputState objects, so the same states can be referenced later.
  val calculatedStates = new HashMap[String, BoxOutputState]()

  def getOutput(user: serving.User, request: GetOutputRequest): GetOutputResponse = {
    val state = getOutput(user, request.id)
    state.kind match {
      case BoxOutputKind.Project =>
        val project = if (state.success.enabled) {
          Some(state.project.viewer.toFE(""))
        } else None
        GetOutputResponse(state.kind, project = project, success = state.success)
    }
  }

  private def getOutput(user: serving.User, stateID: String): BoxOutputState = {
    calculatedStates.synchronized {
      calculatedStates.get(stateID)
    } match {
      case None => throw new AssertionError(s"BoxOutputState state identified by $stateID not found")
      case Some(state: BoxOutputState) => state
    }
  }

  def getProgress(
    user: serving.User, request: GetProgressRequest): GetProgressResponse = {
    val progress = request.stateIDs.map {
      stateID =>
        val state = getOutput(user, stateID)
        if (state.success.enabled) {
          state.kind match {
            case BoxOutputKind.Project => (stateID, state.project.state.progress)
            case _ => throw new AssertionError(s"Unknown kind ${state.kind}")
          }
        } else {
          (stateID, List())
        }
    }.toMap.mapValues {
      progressList =>
        Progress(
          computed = progressList.count(_ == 1.0),
          inProgress = progressList.count(x => x < 1.0 && x > 0.0),
          notYetStarted = progressList.count(_ == 0.0),
          failed = progressList.count(_ < 0.0)
        )
    }.view.force
    GetProgressResponse(progress)
  }

  def setWorkspace(
    user: serving.User, request: SetWorkspaceRequest): Unit = metaManager.synchronized {
    val f = DirectoryEntry.fromName(request.name)
    assert(f.exists, s"Project ${request.name} does not exist.")
    f.assertWriteAllowedFrom(user)
    f match {
      case f: WorkspaceFrame =>
        val cp = request.workspace.checkpoint(previous = f.checkpoint)
        f.setCheckpoint(cp)
      case _ => throw new AssertionError(s"${request.name} is not a workspace.")
    }
  }

  def boxCatalog(user: serving.User, request: serving.Empty): BoxCatalogResponse = {
    BoxCatalogResponse(ops.operationIds.toList.map(ops.getBoxMetadata(_)))
  }

  def getOperationMeta(user: serving.User, request: GetOperationMetaRequest): FEOperationMeta = {
    val ws = getWorkspaceByName(user, request.workspace)
    val op = ws.getOperation(user, ops, request.box)
    op.toFE
  }
}

object WorkspaceControllerJsonFormatters {
  import com.lynxanalytics.biggraph.serving.FrontendJson.fFEStatus
  import WorkspaceJsonFormatters._
  implicit val fBoxOutputIDPair = json.Json.format[BoxOutputIDPair]
  implicit val fProgress = json.Json.format[Progress]
}
