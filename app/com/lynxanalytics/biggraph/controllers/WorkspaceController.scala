// Methods for manipulating workspaces.
package com.lynxanalytics.biggraph.controllers

import scala.collection.mutable.HashMap
import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.frontend_operations.Operations
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_operations.DynamicValue
import com.lynxanalytics.biggraph.graph_util.Timestamp
import com.lynxanalytics.biggraph.serving
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }

case class WorkspaceReference(
    top: String, // The name of the top-level workspace.
    customBoxStack: List[String] = List()) // The ID of the custom boxes we have "dived" into.
case class BoxOutputInfo(boxOutput: BoxOutput, stateId: String, success: FEStatus, kind: String)
case class GetWorkspaceResponse(
    name: String,
    workspace: Workspace,
    outputs: List[BoxOutputInfo],
    summaries: Map[String, String],
    progress: Map[String, Option[Progress]],
    canUndo: Boolean,
    canRedo: Boolean)
case class SetWorkspaceRequest(reference: WorkspaceReference, workspace: Workspace)
case class GetOperationMetaRequest(workspace: WorkspaceReference, box: String)
case class Progress(computed: Int, inProgress: Int, notYetStarted: Int, failed: Int)
case class GetProjectOutputRequest(id: String, path: String)
case class GetTableOutputRequest(id: String, sampleRows: Int)
case class TableColumn(name: String, dataType: String)
case class GetTableOutputResponse(header: List[TableColumn], data: List[List[DynamicValue]])
case class GetPlotOutputRequest(id: String)
case class GetPlotOutputResponse(json: FEScalar)
case class GetVisualizationOutputRequest(id: String)
case class CreateWorkspaceRequest(name: String)
case class BoxCatalogRequest(ref: WorkspaceReference)
case class BoxCatalogResponse(boxes: List[BoxMetadata], categories: List[FEOperationCategory])
case class CreateSnapshotRequest(name: String, id: String)
case class GetExportResultRequest(stateId: String)
case class GetExportResultResponse(parameters: Map[String, String], result: FEScalar)
case class RunWorkspaceRequest(workspace: Workspace, parameters: Map[String, String])
case class RunWorkspaceResponse(
    outputs: List[BoxOutputInfo],
    summaries: Map[String, String],
    progress: Map[String, Option[Progress]])
case class ImportBoxRequest(box: Box, ref: Option[WorkspaceReference])

// An instrument is like a box. But we do not want to place it and save it in the workspace.
// It always has 1 input and 1 output, so the connections do not need to be expressed either.
case class Instrument(
    operationId: String,
    parameters: Map[String, String],
    parametricParameters: Map[String, String])
case class InstrumentState(
    stateId: String,
    kind: String,
    error: String)
case class GetInstrumentedStateRequest(
    workspace: WorkspaceReference,
    inputStateId: String, // State at the start of the instrument chain.
    instruments: List[Instrument]) // Instrument chain. (N instruments.)
case class GetInstrumentedStateResponse(
    metas: List[FEOperationMeta], // Metadata for each instrument. (N metadatas.)
    states: List[InstrumentState]) // Initial state + the output of each instrument. (N+1 states.)

class WorkspaceController(env: SparkFreeEnvironment) {
  implicit val metaManager = env.metaGraphManager
  implicit val entityProgressManager: EntityProgressManager = env.entityProgressManager

  val ops = new Operations(env)
  BuiltIns.createBuiltIns(env.metaGraphManager)

  private def assertNameNotExists(name: String) = {
    assert(!DirectoryEntry.fromName(name).exists, s"Entry '$name' already exists.")
  }

  def createWorkspace(
    user: serving.User, request: CreateWorkspaceRequest): Unit = metaManager.synchronized {
    assertNameNotExists(request.name)
    val entry = DirectoryEntry.fromName(request.name)
    entry.assertParentWriteAllowedFrom(user)
    val w = entry.asNewWorkspaceFrame()
  }

  private def getWorkspaceFrame(
    user: serving.User, name: String): WorkspaceFrame = metaManager.synchronized {
    val f = DirectoryEntry.fromName(name)
    assert(f.exists, s"Entry ${name} does not exist.")
    f.assertReadAllowedFrom(user)
    f match {
      case f: WorkspaceFrame => f
      case _ => throw new AssertionError(s"${name} is not a workspace.")
    }
  }

  case class ResolvedWorkspaceReference(user: serving.User, ref: WorkspaceReference) {
    val topWorkspace = getWorkspaceFrame(user, ref.top).workspace
    val (ws, params, name) = ref.customBoxStack.foldLeft((topWorkspace, Map[String, String](), ref.top)) {
      case ((ws, params, _), boxId) =>
        val ctx = ws.context(user, ops, params)
        val op = ctx.getOperation(boxId).asInstanceOf[CustomBoxOperation]
        val cws = op.connectedWorkspace
        (cws, op.getParams, op.context.box.operationId)
    }
    lazy val frame = getWorkspaceFrame(user, name)
  }

  def getWorkspace(
    user: serving.User, request: WorkspaceReference): GetWorkspaceResponse = {
    val ref = ResolvedWorkspaceReference(user, request)
    val run = try runWorkspace(user, RunWorkspaceRequest(ref.ws, ref.params)) catch {
      case t: Throwable =>
        log.error(s"Could not execute $request", t)
        // We can still return the "cold" data that is available without execution.
        // This makes it at least possible to press Undo.
        RunWorkspaceResponse(List(), Map(), Map())
    }
    GetWorkspaceResponse(
      ref.name, ref.ws, run.outputs, run.summaries, run.progress,
      canUndo = ref.frame.currentState.previousCheckpoint.nonEmpty,
      canRedo = ref.frame.nextCheckpoint.nonEmpty)
  }

  def runWorkspace(
    user: serving.User, request: RunWorkspaceRequest): RunWorkspaceResponse = {
    val context = request.workspace.context(user, ops, request.parameters)
    val states = context.allStates
    val statesWithId = states.mapValues((_, Timestamp.toString)).view.force
    calculatedStates.synchronized {
      for ((_, (boxOutputState, id)) <- statesWithId) {
        calculatedStates(id) = boxOutputState
      }
    }
    val stateInfo = statesWithId.toList.map {
      case (boxOutput, (boxOutputState, stateId)) =>
        BoxOutputInfo(boxOutput, stateId, boxOutputState.success, boxOutputState.kind)
    }
    def crop(s: String): String = {
      val maxLength = 50
      if (s.length > maxLength) { s.substring(0, maxLength - 3) + "..." } else { s }
    }
    val summaries = request.workspace.boxes.map(
      box => box.id -> crop(
        try { context.getOperationForStates(box, states).summary }
        catch {
          case t: Throwable =>
            log.error(s"Error while generating summary for $box in $request.", t)
            box.operationId
        })).toMap
    val progress = getProgress(user, statesWithId.values.map(_._2).toSeq)
    RunWorkspaceResponse(stateInfo, summaries, progress)
  }

  // This is for storing the calculated BoxOutputState objects, so the same states can be referenced later.
  val calculatedStates = new HashMap[String, BoxOutputState]()

  def getOutput(user: serving.User, stateId: String): BoxOutputState = {
    calculatedStates.synchronized {
      calculatedStates.get(stateId)
    } match {
      case None => BoxOutputState("error", None, FEStatus(false))
      case Some(state: BoxOutputState) => state
    }
  }

  def getProjectOutput(
    user: serving.User, request: GetProjectOutputRequest): FEProject = {
    val state = getOutput(user, request.id)
    val pathSeq = SubProject.splitPipedPath(request.path).filter(_ != "")
    val project =
      if (state.isVisualization) state.visualization.project
      else state.project
    val viewer = project.viewer.offspringViewer(pathSeq)
    viewer.toFE(request.path)
  }

  def getPlotOutput(
    user: serving.User, request: GetPlotOutputRequest): GetPlotOutputResponse = {
    val state = getOutput(user, request.id)
    val scalar = state.plot
    val fescalar = ProjectViewer.feScalar(scalar, "result", "", Map())
    GetPlotOutputResponse(fescalar)
  }

  import UIStatusSerialization.fTwoSidedUIStatus

  def getVisualizationOutput(
    user: serving.User, request: GetVisualizationOutputRequest): TwoSidedUIStatus = {
    val state = getOutput(user, request.id)
    state.visualization.uiStatus
  }

  def getExportResultOutput(
    user: serving.User, request: GetExportResultRequest): GetExportResultResponse = {
    val state = getOutput(user, request.stateId)
    state.kind match {
      case BoxOutputKind.ExportResult =>
        val scalar = state.exportResult
        val feScalar = ProjectViewer.feScalar(scalar, "result", "", Map())
        val parameters = (state.state.get \ "parameters").as[Map[String, String]]
        GetExportResultResponse(parameters, feScalar)
    }
  }

  def getProgress(user: serving.User, stateIds: Seq[String]): Map[String, Option[Progress]] = {
    val states = stateIds.map(stateId => stateId -> getOutput(user, stateId)).toMap
    states.map {
      case (stateId, state) =>
        if (state.success.enabled) {
          state.kind match {
            case BoxOutputKind.Project => stateId -> Some(state.project.viewer.getProgress)
            case BoxOutputKind.Table =>
              val progress = entityProgressManager.computeProgress(state.table)
              stateId -> Some(List(progress))
            case BoxOutputKind.Plot =>
              val progress = entityProgressManager.computeProgress(state.plot)
              stateId -> Some(List(progress))
            case BoxOutputKind.ExportResult =>
              val progress = entityProgressManager.computeProgress(state.exportResult)
              stateId -> Some(List(progress))
            case BoxOutputKind.Visualization =>
              stateId -> Some(List(1.0))
            case _ => throw new AssertionError(s"Unknown kind ${state.kind}")
          }
        } else {
          stateId -> None
        }
    }.mapValues(option => option.map(
      progressList => Progress(
        computed = progressList.count(_ == 1.0),
        inProgress = progressList.count(x => x < 1.0 && x > 0.0),
        notYetStarted = progressList.count(_ == 0.0),
        failed = progressList.count(_ < 0.0)))).view.force
  }

  def createSnapshot(
    user: serving.User, request: CreateSnapshotRequest): Unit = {
    def calculatedState() = calculatedStates.synchronized {
      calculatedStates(request.id)
    }
    createSnapshotFromState(user, request.name, calculatedState)
  }

  def createSnapshotFromState(user: serving.User, path: String, state: () => BoxOutputState): Unit = {
    val entry = DirectoryEntry.fromName(path)
    entry.assertWriteAllowedFrom(user)
    entry.asNewSnapshotFrame(state())
  }

  def setWorkspace(
    user: serving.User, request: SetWorkspaceRequest): Unit = metaManager.synchronized {
    val f = getWorkspaceFrame(user, ResolvedWorkspaceReference(user, request.reference).name)
    f.assertWriteAllowedFrom(user)
    val cp = request.workspace.checkpoint(previous = f.checkpoint)
    f.setCheckpoint(cp)
  }

  def setAndGetWorkspace(
    user: serving.User, request: SetWorkspaceRequest): GetWorkspaceResponse = metaManager.synchronized {
    setWorkspace(user, request)
    getWorkspace(user, request.reference)
  }

  def undoWorkspace(
    user: serving.User, request: WorkspaceReference): GetWorkspaceResponse = metaManager.synchronized {
    val f = getWorkspaceFrame(user, ResolvedWorkspaceReference(user, request).name)
    f.assertWriteAllowedFrom(user)
    f.undo()
    getWorkspace(user, request)
  }

  def redoWorkspace(
    user: serving.User, request: WorkspaceReference): GetWorkspaceResponse = metaManager.synchronized {
    val f = getWorkspaceFrame(user, ResolvedWorkspaceReference(user, request).name)
    f.assertWriteAllowedFrom(user)
    f.redo()
    getWorkspace(user, request)
  }

  private def operationIsWorkspace(user: serving.User, opId: String): Boolean = {
    val entry = DirectoryEntry.fromName(opId)
    entry.exists && entry.isWorkspace && entry.readAllowedFrom(user)
  }

  def boxCatalog(user: serving.User, request: BoxCatalogRequest): BoxCatalogResponse = {
    // We need the custom boxes of the current workspace, if the call comes
    // from the UI.
    val customBoxOperationIds = if (!request.ref.top.isEmpty()) {
      val ref = ResolvedWorkspaceReference(
        user,
        WorkspaceReference(request.ref.top, request.ref.customBoxStack))
      ref.ws.boxes.map(_.operationId).filter(operationIsWorkspace(user, _))
    } else {
      List()
    }
    BoxCatalogResponse(
      ops.operationsRelevantToWorkspace(
        user, request.ref.top, customBoxOperationIds).toList.map(ops.getBoxMetadata(_)),
      ops.getCategories(user))
  }

  def getOperationMeta(user: serving.User, request: GetOperationMetaRequest): FEOperationMeta = {
    getOperation(user, request).toFE
  }

  def getOperationInputTables(user: serving.User, request: GetOperationMetaRequest): Map[String, ProtoTable] = {
    import Operation.Implicits._
    getOperation(user, request).getInputTables()
  }

  def getOperation(user: serving.User, request: GetOperationMetaRequest): Operation = {
    val ref = ResolvedWorkspaceReference(user, request.workspace)
    val ctx = ref.ws.context(user, ops, ref.params)
    ctx.getOperation(request.box)
  }

  @annotation.tailrec
  private def instrumentStatesAndMetas(
    ctx: WorkspaceExecutionContext,
    instruments: List[Instrument],
    states: List[BoxOutputState],
    opMetas: List[FEOperationMeta]): (List[BoxOutputState], List[FEOperationMeta]) = {
    val next = if (instruments.isEmpty) {
      None
    } else {
      val instr = instruments.head
      val state = states.last
      val meta = ops.getBoxMetadata(instr.operationId)
      assert(
        meta.inputs.size == 1,
        s"${instr.operationId} has ${meta.inputs.size} inputs instead of 1.")
      assert(
        meta.outputs.size == 1,
        s"${instr.operationId} has ${meta.outputs.size} outputs instead of 1.")
      val box = Box(
        id = "",
        operationId = instr.operationId,
        parameters = instr.parameters,
        x = 0,
        y = 0,
        // It does not matter where the inputs come from. Using "null" for BoxOutput.
        inputs = Map(meta.inputs.head -> null),
        parametricParameters = instr.parametricParameters)
      val op = box.getOperation(ctx, Map(meta.inputs.head -> state))
      val newState = box.orErrors(meta) { op.getOutputs }(box.output(meta.outputs.head))
      Some((newState, op.toFE))
    }
    next match {
      case Some((newState, newMeta)) if newState.isError =>
        // Pretend there are no more instruments. This allows the error state to be seen.
        (states :+ newState, opMetas :+ newMeta)
      case Some((newState, newMeta)) =>
        instrumentStatesAndMetas(ctx, instruments.tail, states :+ newState, opMetas :+ newMeta)
      case None => (states, opMetas)
    }
  }

  def getInstrumentedState(
    user: serving.User, request: GetInstrumentedStateRequest): GetInstrumentedStateResponse = {
    val ref = ResolvedWorkspaceReference(user, request.workspace)
    val ctx = ref.ws.context(user, ops, ref.params)
    val inputState = getOutput(user, request.inputStateId)
    var (states, opMetas) = instrumentStatesAndMetas(
      ctx, request.instruments, List(inputState), List[FEOperationMeta]())
    val instrumentStates = calculatedStates.synchronized {
      states.map { state =>
        val id = Timestamp.toString
        calculatedStates(id) = state
        InstrumentState(id, state.kind, state.success.disabledReason)
      }
    }
    GetInstrumentedStateResponse(opMetas, instrumentStates)
  }
}
