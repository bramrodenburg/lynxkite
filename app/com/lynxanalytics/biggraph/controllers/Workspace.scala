// Things that go inside a "boxes & arrows" workspace.
package com.lynxanalytics.biggraph.controllers

import play.api.libs.json
import com.lynxanalytics.biggraph._
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import graph_api.MetaGraphManager.StringAsUUID

import scala.annotation.tailrec

case class Workspace(
    boxes: List[Box]) {
  val boxMap = boxes.map(b => b.id -> b).toMap
  assert(boxMap.size == boxes.size, {
    val dups = boxes.groupBy(_.id).filter(_._2.size > 1).keys
    s"Duplicate box name: ${dups.mkString(", ")}"
  })

  assert(anchor.operationId == "Anchor", "Anchor box is missing.")

  def anchor = findBox("anchor")

  def findBox(id: String): Box = {
    assert(boxMap.contains(id), s"Cannot find box: $id")
    boxMap(id)
  }

  def parametersMeta: Seq[CustomOperationParameterMeta] = {
    OperationParams.ParametersParam.parse(anchor.parameters.get("parameters"))
  }

  // This workspace as a custom box.
  def getBoxMetadata(name: String): BoxMetadata = {
    val description = anchor.parameters.getOrElse("description", "")
    val icon = anchor.parameters.getOrElse("icon", "")
    val inputs = boxes.filter(_.operationId == "Input").flatMap(b => b.parameters.get("name"))
    val outputs = boxes.filter(_.operationId == "Output").flatMap(b => b.parameters.get("name"))
    BoxMetadata(
      categoryId = Workspace.customBoxesCategory,
      icon = if (icon.nonEmpty) icon else "/images/icons/superpowers.png",
      color = "natural",
      operationId = name,
      inputs = inputs,
      outputs = outputs,
      description = Some(description))
  }

  def workspaceExecutionContextParameters(workspaceParameters: Map[String, String]) = {
    val pm = parametersMeta
    val defaultParameters = pm.map(p => p.id -> p.defaultValue).toMap
    val unrecognized = workspaceParameters.keySet -- pm.map(_.id).toSet
    assert(unrecognized.isEmpty, s"Unrecognized parameter: ${unrecognized.mkString(", ")}")
    defaultParameters ++ workspaceParameters
  }

  def context(
    user: serving.User, ops: OperationRepository, workspaceParameters: Map[String, String]) = {
    WorkspaceExecutionContext(
      this, user, ops, workspaceExecutionContextParameters(workspaceParameters))
  }

  def checkpoint(previous: String = null)(implicit manager: graph_api.MetaGraphManager): String = {
    manager.checkpointRepo.checkpointState(
      CheckpointObject(workspace = Some(this)),
      previous).checkpoint.get
  }
  case class Dependencies(topologicalOrder: List[Box], withCircularDependency: List[Box])

  // Tries to determine a topological order among boxes. All boxes with a circular dependency and
  // ones that depend on another with a circular dependency are returned unordered.
  private[controllers] def discoverDependencies: Dependencies = {
    val outEdges: Map[String, Seq[String]] = {
      val edges = boxes.flatMap(dst => dst.inputs.toSeq.map(input => input._2.boxId -> dst.id))
      edges.groupBy(_._1).mapValues(_.map(_._2).toSeq)
    }

    // Determines the topological order by selecting a node without in-edges, removing the node and
    // its connections and calling itself on the remaining graph.
    @tailrec
    def discover(
      reversedTopologicalOrder: List[Box],
      remainingBoxInDegrees: List[(Box, Int)]): Dependencies =
      if (remainingBoxInDegrees.isEmpty) {
        Dependencies(reversedTopologicalOrder.reverse, List())
      } else {
        val (nextBox, lowestDegree) = remainingBoxInDegrees.minBy(_._2)
        if (lowestDegree > 0) {
          Dependencies(
            topologicalOrder = reversedTopologicalOrder.reverse,
            withCircularDependency = remainingBoxInDegrees.map(_._1))
        } else {
          val dependants = outEdges.getOrElse(nextBox.id, Seq())
          val updatedInDegrees = remainingBoxInDegrees.withFilter(_._1 != nextBox)
            .map {
              case (box, degree) => (box, degree - dependants.filter(_ == box.id).size)
            }.map(identity)
          discover(nextBox :: reversedTopologicalOrder, updatedInDegrees)
        }
      }

    val inDegrees: List[(Box, Int)] = boxes.map(box => box -> box.inputs.size)
    discover(List(), inDegrees)
  }
}

object Workspace {
  def from(boxes: Box*): Workspace = {
    // Automatically add anchor if missing. Helps with tests.
    if (boxes.find(_.id == "anchor").nonEmpty) new Workspace(boxes.toList)
    else new Workspace(Box("anchor", "Anchor", Map(), 0, 0, Map()) +: boxes.toList)
  }

  val customBoxesCategory = "Custom boxes"
}

// Everything required for executing things in a workspace.
case class WorkspaceExecutionContext(
    ws: Workspace,
    user: serving.User,
    ops: OperationRepository,
    workspaceParameters: Map[String, String]) {

  def allStates: Map[BoxOutput, BoxOutputState] = {
    val dependencies = ws.discoverDependencies
    val statesWithoutCircularDependency = dependencies.topologicalOrder
      .foldLeft(Map[BoxOutput, BoxOutputState]()) {
        (states, box) =>
          util.Try(outputStatesOfBox(box, states)).getOrElse(Map()) ++ states
      }
    val statesWithCircularDependency = dependencies.withCircularDependency.flatMap { box =>
      val meta = ops.getBoxMetadata(box.operationId)
      meta.outputs.map { o =>
        box.output(o) -> BoxOutputState.error("Can not compute state due to circular dependencies.")
      }
    }.toMap
    statesWithoutCircularDependency ++ statesWithCircularDependency
  }

  private def outputStatesOfBox(
    box: Box, inputStates: Map[BoxOutput, BoxOutputState]): Map[BoxOutput, BoxOutputState] = {
    val meta = ops.getBoxMetadata(box.operationId)

    val unconnectedInputs = meta.inputs.filterNot(conn => box.inputs.contains(conn))
    if (unconnectedInputs.nonEmpty) {
      val list = unconnectedInputs.mkString(", ")
      box.allOutputsWithError(meta, s"Input $list is not connected.")
    } else if (meta.outputs.isEmpty) {
      Map() // No reason to execute the box if it has no outputs.
    } else {
      val inputs = box.inputs.map { case (id, output) => id -> inputStates(output) }
      val inputErrors = inputs.filter(_._2.isError)
      if (inputErrors.nonEmpty) {
        val list = inputErrors.keys.mkString(", ")
        box.allOutputsWithError(meta, s"Input $list has an error.")
      } else {
        box.orErrors(meta) { box.execute(this, inputs) }
      }
    }
  }

  def getOperationForStates(box: Box, states: Map[BoxOutput, BoxOutputState]): Operation = {
    val meta = ops.getBoxMetadata(box.operationId)
    for (i <- meta.inputs) {
      assert(box.inputs.contains(i), s"Input $i is not connected.")
    }
    val inputs = box.inputs.map { case (id, output) => id -> states(output) }
    assert(!inputs.exists(_._2.isError), {
      val errors = inputs.filter(_._2.isError).map(_._1).mkString(", ")
      s"Input $errors has an error."
    })
    box.getOperation(this, inputs)
  }

  def getOperation(boxId: String): Operation = getOperationForStates(ws.findBox(boxId), allStates)
}

case class Box(
    id: String,
    operationId: String,
    parameters: Map[String, String],
    x: Double,
    y: Double,
    inputs: Map[String, BoxOutput],
    parametricParameters: Map[String, String] = Map()) {

  def output(id: String) = BoxOutput(this.id, id)

  def getOperation(
    ctx: WorkspaceExecutionContext,
    inputStates: Map[String, BoxOutputState]): Operation = {
    assert(
      inputs.keys == inputStates.keys,
      s"Input mismatch: $inputStates does not match $inputs")
    ctx.ops.opForBox(ctx.user, this, inputStates, ctx.workspaceParameters)
  }

  def execute(
    ctx: WorkspaceExecutionContext,
    inputStates: Map[String, BoxOutputState]): Map[BoxOutput, BoxOutputState] = {
    val op = getOperation(ctx, inputStates)
    val outputStates = op.getOutputs
    outputStates
  }

  def allOutputsWithError(meta: BoxMetadata, msg: String): Map[BoxOutput, BoxOutputState] = {
    meta.outputs.map {
      o => output(o) -> BoxOutputState.error(msg)
    }.toMap
  }

  def orErrors(meta: BoxMetadata)(
    f: => Map[BoxOutput, BoxOutputState]): Map[BoxOutput, BoxOutputState] = {
    try f catch {
      case ex: Throwable =>
        log.info(s"Failed to execute $this:", ex)
        meta.outputs.map {
          o => output(o) -> BoxOutputState(BoxOutputKind.Error, None, FEStatus.from(ex))
        }.toMap
    }
  }
}

case class BoxOutput(
    boxId: String,
    id: String)

case class BoxMetadata(
    categoryId: String,
    icon: String,
    color: String,
    operationId: String,
    inputs: List[String],
    outputs: List[String],
    description: Option[String] = None,
    htmlId: Option[String] = None)

object BoxOutputKind {
  val Project = "project"
  val Table = "table"
  val ExportResult = "exportResult"
  val Plot = "plot"
  val Error = "error"
  val Visualization = "visualization"
  val validKinds = Set(Project, Table, Error, ExportResult, Plot, Visualization)

  def assertKind(kind: String): Unit =
    assert(validKinds.contains(kind), s"Unknown connection type: $kind")
}

case class VisualizationState(
    uiStatus: TwoSidedUIStatus,
    project: RootProjectEditor)
object VisualizationState {
  def fromString(uiStatus: String, project: RootProjectEditor): VisualizationState = {
    import UIStatusSerialization.fTwoSidedUIStatus
    val uiStatusJson = json.Json.parse(uiStatus).as[TwoSidedUIStatus]
    VisualizationState(
      uiStatusJson,
      project)
  }
}

object BoxOutputState {
  // Cannot call these "apply" due to the JSON formatter macros.
  def from(project: CommonProjectState): BoxOutputState = {
    import CheckpointRepository._ // For JSON formatters.
    BoxOutputState(BoxOutputKind.Project, Some(json.Json.toJson(project)))
  }
  def from(project: ProjectEditor): BoxOutputState = from(project.rootState)

  def from(table: graph_api.Table): BoxOutputState = {
    BoxOutputState(BoxOutputKind.Table, Some(json.Json.obj("guid" -> table.gUID)))
  }

  def plot(plot: graph_api.Scalar[String]) = {
    BoxOutputState(BoxOutputKind.Plot, Some(json.Json.obj("guid" -> plot.gUID)))
  }

  def from(
    exportResult: graph_api.Scalar[String],
    params: Map[String, String]): BoxOutputState = {
    BoxOutputState(BoxOutputKind.ExportResult, Some(json.Json.obj(
      "guid" -> exportResult.gUID, "parameters" -> params)))
  }

  def error(msg: String): BoxOutputState = {
    BoxOutputState(BoxOutputKind.Error, None, FEStatus.disabled(msg))
  }

  def visualization(v: VisualizationState): BoxOutputState = {
    import UIStatusSerialization.fTwoSidedUIStatus
    import CheckpointRepository._
    BoxOutputState(
      BoxOutputKind.Visualization,
      Some(json.Json.obj(
        "uiStatus" -> v.uiStatus,
        "project" -> json.Json.toJson(v.project.rootState))))
  }
}

case class BoxOutputState(
    kind: String,
    state: Option[json.JsValue],
    success: FEStatus = FEStatus.enabled) {
  BoxOutputKind.assertKind(kind)
  assert(
    success.enabled ^ (state.isEmpty || state.get == null),
    "State should be present iff computation was successful")

  def isError = !success.enabled
  def isProject = kind == BoxOutputKind.Project
  def isTable = kind == BoxOutputKind.Table
  def isPlot = kind == BoxOutputKind.Plot
  def isExportResult = kind == BoxOutputKind.ExportResult
  def isVisualization = kind == BoxOutputKind.Visualization

  def projectState: CommonProjectState = {
    import CheckpointRepository.fCommonProjectState
    success.check()
    assert(isProject, s"Tried to access '$kind' as 'project'.")
    state.get.as[CommonProjectState]
  }

  def project(implicit m: graph_api.MetaGraphManager): RootProjectEditor = {
    new RootProjectEditor(projectState)
  }

  def table(implicit manager: graph_api.MetaGraphManager): graph_api.Table = {
    success.check()
    assert(isTable, s"Tried to access '$kind' as 'table'.")
    manager.table((state.get \ "guid").as[String].asUUID)
  }

  def plot(implicit manager: graph_api.MetaGraphManager): graph_api.Scalar[String] = {
    success.check()
    assert(isPlot, s"Tried to access '$kind' as 'Plot'.")
    manager.scalarOf[String]((state.get \ "guid").as[String].asUUID)
  }

  def exportResult(implicit manager: graph_api.MetaGraphManager): graph_api.Scalar[String] = {
    success.check()
    assert(isExportResult, s"Tried to access '$kind' as 'exportResult'.")
    manager.scalarOf[String]((state.get \ "guid").as[String].asUUID)
  }

  def visualization(implicit manager: graph_api.MetaGraphManager): VisualizationState = {
    import UIStatusSerialization.fTwoSidedUIStatus
    import CheckpointRepository.fCommonProjectState
    success.check()
    assert(isVisualization, s"Tried to access '$kind' as 'visualization'.")
    val projectState = (state.get \ "project").as[CommonProjectState]
    VisualizationState(
      (state.get \ "uiStatus").as[TwoSidedUIStatus],
      new RootProjectEditor(projectState))
  }

  // JsonMigration may want to update GUIDs of updated operations.
  def mapGuids(change: java.util.UUID => java.util.UUID): BoxOutputState = {
    kind match {
      case BoxOutputKind.Project => BoxOutputState.from(projectState.mapGuids(change))
      case BoxOutputKind.Table => defaultGuidMapper(change)
      case BoxOutputKind.Plot => defaultGuidMapper(change)
      case BoxOutputKind.ExportResult => defaultGuidMapper(change)
      case BoxOutputKind.Visualization => this // Contains no GUIDs.
    }
  }

  private def defaultGuidMapper(change: java.util.UUID => java.util.UUID): BoxOutputState = {
    val oldState = state.get
    val oldGuid = (oldState \ "guid").as[String].asUUID
    val newGuid = change(oldGuid).toString
    val newState = oldState.as[json.JsObject] ++ json.Json.obj("guid" -> newGuid)
    this.copy(state = Some(newState))
  }
}

object WorkspaceJsonFormatters {
  implicit val fFEStatus = FEStatus.format
  implicit val fBoxOutput = json.Json.format[BoxOutput]
  implicit val fBoxOutputState = json.Json.format[BoxOutputState]
  implicit val fBox = json.Json.format[Box]
  implicit val fBoxMetadata = json.Json.format[BoxMetadata]
  implicit val fWorkspace = json.Json.format[Workspace]
}
