package com.lynxanalytics.biggraph.serving

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.mvc
import play.api.libs.json
import com.lynxanalytics.biggraph.BigGraphProductionEnvironment
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.controllers._
import com.lynxanalytics.biggraph.graph_operations.DynamicValue
import com.lynxanalytics.biggraph.graph_util.Filename
import play.api.libs.functional.syntax.toContraFunctorOps
import play.api.libs.json.Json.toJsFieldJsValueWrapper

object LynxUser extends securesocial.core.Authorization {
  def isAuthorized(user: securesocial.core.Identity) = user.email.get.endsWith("@lynxanalytics.com")
}

class JsonServer extends mvc.Controller with securesocial.core.SecureSocial {
  def testMode = play.api.Play.maybeApplication == None
  def productionMode = !testMode && play.api.Play.current.configuration.getString("application.secret").nonEmpty

  def action[A](parser: mvc.BodyParser[A])(block: mvc.Request[A] => mvc.Result): mvc.Action[A] = {
    // Turn off authentication in development mode.
    if (productionMode) {
      SecuredAction(authorize = LynxUser, ajaxCall = true)(parser)(block(_))
    } else {
      mvc.Action(parser)(block(_))
    }
  }

  // It would be great to merge this with action[A], but the compiler crashes when I try.
  def asyncAction[A](parser: mvc.BodyParser[A])(
    block: mvc.Request[A] => concurrent.Future[mvc.SimpleResult]): mvc.Action[A] = {
    // Turn off authentication in development mode.
    if (productionMode) {
      SecuredAction(authorize = LynxUser, ajaxCall = true).async(parser)(block(_))
    } else {
      mvc.Action.async(parser)(block(_))
    }
  }

  def jsonPost[I: json.Reads, O: json.Writes](handler: I => O) = {
    action(parse.json) { request =>
      log.info(s"POST ${request.path} ${request.body}")
      val i = request.body.as[I]
      Ok(json.Json.toJson(handler(i)))
    }
  }

  def jsonQuery[I: json.Reads, R](request: mvc.Request[mvc.AnyContent])(handler: I => R): R = {
    val key = "q"
    val value = request.getQueryString(key)
    assert(value.nonEmpty, s"Missing query parameter $key.")
    val s = value.get
    log.info(s"GET ${request.path} $s")
    val i = json.Json.parse(s).as[I]
    handler(i)
  }

  def jsonGet[I: json.Reads, O: json.Writes](handler: I => O) = {
    action(parse.anyContent) { request =>
      jsonQuery(request) { i: I =>
        try {
          Ok(json.Json.toJson(handler(i)))
        } catch {
          case flying: FlyingResult => flying.result
        }
      }
    }
  }

  def jsonFuture[I: json.Reads, O: json.Writes](handler: I => concurrent.Future[O]) = {
    asyncAction(parse.anyContent) { request =>
      jsonQuery(request) { i: I =>
        handler(i).map(o => Ok(json.Json.toJson(o)))
      }
    }
  }
}

case class Empty(
  fake: Int = 0) // Needs fake field as JSON inception doesn't work otherwise.

object ProductionJsonServer extends JsonServer {
  /**
   * Implicit JSON inception
   *
   * json.Json.toJson needs one for every incepted case class,
   * they need to be ordered so that everything is declared before use.
   */

  // TODO: do this without a fake field, e.g. by not using inception.
  implicit val rEmpty = json.Json.reads[Empty]
  implicit val wUnit = new json.Writes[Unit] {
    def writes(u: Unit) = json.Json.obj()
  }

  implicit val rVertexSetRequest = json.Json.reads[VertexSetRequest]
  implicit val wFEStatus = json.Json.writes[FEStatus]
  implicit val wUIValue = json.Json.writes[UIValue]
  implicit val wUIValues = json.Json.writes[UIValues]
  implicit val wFEOperationParameterMeta = json.Json.writes[FEOperationParameterMeta]
  implicit val wFEOperationMeta = json.Json.writes[FEOperationMeta]
  implicit val wFEOperationMetas = json.Json.writes[FEOperationMetas]
  implicit val wFEEdgeBundle = json.Json.writes[FEEdgeBundle]
  implicit val wFEVertexSet = json.Json.writes[FEVertexSet]

  implicit val rFEOperationSpec = json.Json.reads[FEOperationSpec]

  implicit val rSparkStatusRequest = json.Json.reads[SparkStatusRequest]
  implicit val rSetClusterNumInstanceRequest = json.Json.reads[SetClusterNumInstanceRequest]
  implicit val wSparkStatusResponse = json.Json.writes[SparkStatusResponse]
  implicit val wSparkClusterStatusResponse = json.Json.writes[SparkClusterStatusResponse]

  implicit val rFEVertexAttributeFilter = json.Json.reads[FEVertexAttributeFilter]
  implicit val rAxisOptions = json.Json.reads[AxisOptions]
  implicit val rVertexDiagramSpec = json.Json.reads[VertexDiagramSpec]
  implicit val wDynamicValue = json.Json.writes[DynamicValue]
  implicit val wFEVertex = json.Json.writes[FEVertex]
  implicit val wVertexDiagramResponse = json.Json.writes[VertexDiagramResponse]

  implicit val rBundleSequenceStep = json.Json.reads[BundleSequenceStep]
  implicit val rEdgeDiagramSpec = json.Json.reads[EdgeDiagramSpec]
  implicit val wFEEdge = json.Json.writes[FEEdge]
  implicit val wEdgeDiagramResponse = json.Json.writes[EdgeDiagramResponse]

  implicit val rFEGraphRequest = json.Json.reads[FEGraphRequest]
  implicit val wFEGraphResponse = json.Json.writes[FEGraphResponse]

  implicit val rCenterRequest = json.Json.reads[CenterRequest]
  implicit val wCenterResponse = json.Json.writes[CenterResponse]

  implicit val rHistogramSpec = json.Json.reads[HistogramSpec]
  implicit val wHistogramResponse = json.Json.writes[HistogramResponse]

  implicit val rScalarValueRequest = json.Json.reads[ScalarValueRequest]

  implicit val rCreateProjectRequest = json.Json.reads[CreateProjectRequest]
  implicit val rDiscardProjectRequest = json.Json.reads[DiscardProjectRequest]
  implicit val rProjectRequest = json.Json.reads[ProjectRequest]
  implicit val rProjectOperationRequest = json.Json.reads[ProjectOperationRequest]
  implicit val rProjectFilterRequest = json.Json.reads[ProjectFilterRequest]
  implicit val rForkProjectRequest = json.Json.reads[ForkProjectRequest]
  implicit val rUndoProjectRequest = json.Json.reads[UndoProjectRequest]
  implicit val rRedoProjectRequest = json.Json.reads[RedoProjectRequest]
  implicit val wOperationCategory = json.Json.writes[OperationCategory]
  implicit val wFEAttribute = json.Json.writes[FEAttribute]
  implicit val wFESegmentation = json.Json.writes[FESegmentation]
  implicit val wFEProject = json.Json.writes[FEProject]
  implicit val wSplash = json.Json.writes[Splash]

  implicit val wDemoModeStatusResponse = json.Json.writes[DemoModeStatusResponse]

  // File upload.
  def upload = {
    action(parse.multipartFormData) { request =>
      val upload = request.body.file("file").get
      log.info(s"upload: ${upload.filename}")
      val dataRepo = BigGraphProductionEnvironment.dataManager.repositoryPath
      val output = dataRepo / "uploads" / upload.filename.replace(" ", "_")
      val stream = output.create()
      try java.nio.file.Files.copy(upload.ref.file.toPath, stream)
      finally stream.close()
      Ok(output.fullString)
    }
  }

  def download = action(parse.anyContent) { request =>
    import play.api.libs.concurrent.Execution.Implicits._
    import scala.collection.JavaConversions._
    log.info(s"download: ${request.path}")
    val path = Filename(request.getQueryString("path").get)
    val name = Filename(request.getQueryString("name").get)
    // For now this is about CSV downloads. We want to read the "header" file and then the "data" directory.
    val files = Seq(path / "header") ++ (path / "data" / "*").list
    val length = files.map(_.length).sum
    log.info(s"downloading $length bytes: $files")
    val stream = new java.io.SequenceInputStream(files.view.map(_.open).iterator)
    mvc.SimpleResult(
      header = mvc.ResponseHeader(200, Map(
        CONTENT_LENGTH -> length.toString,
        CONTENT_DISPOSITION -> s"attachment; filename=$name.csv")),
      body = play.api.libs.iteratee.Enumerator.fromStream(stream)
    )
  }

  // Methods called by the web framework
  //
  // Play! uses the routings in /conf/routes to execute actions

  val bigGraphController = new BigGraphController(BigGraphProductionEnvironment)
  def vertexSetGet = jsonGet(bigGraphController.vertexSet)
  def applyOp = jsonPost(bigGraphController.applyOp)
  def startingOperationsGet = jsonGet(bigGraphController.startingOperations)
  def startingVertexSetsGet = jsonGet(bigGraphController.startingVertexSets)
  def createProject = jsonPost(bigGraphController.createProject)
  def discardProject = jsonPost(bigGraphController.discardProject)
  def projectOp = jsonPost(bigGraphController.projectOp)
  def project = jsonGet(bigGraphController.project)
  def splash = jsonGet(bigGraphController.splash)
  def filterProject = jsonPost(bigGraphController.filterProject)
  def forkProject = jsonPost(bigGraphController.forkProject)
  def undoProject = jsonPost(bigGraphController.undoProject)
  def redoProject = jsonPost(bigGraphController.redoProject)

  val sparkClusterController = new SparkClusterController(BigGraphProductionEnvironment)
  def getClusterStatus = jsonGet(sparkClusterController.getClusterStatus)
  def setClusterNumInstances = jsonGet(sparkClusterController.setClusterNumInstances)
  def sparkStatus = jsonFuture(sparkClusterController.sparkStatus)
  def sparkCancelJobs = jsonPost(sparkClusterController.sparkCancelJobs)

  val drawingController = new GraphDrawingController(BigGraphProductionEnvironment)
  def vertexDiagram = jsonGet(drawingController.getVertexDiagram)
  def edgeDiagram = jsonGet(drawingController.getEdgeDiagram)
  def complexView = jsonGet(drawingController.getComplexView)
  def center = jsonGet(drawingController.getCenter)
  def histo = jsonGet(drawingController.getHistogram)
  def scalarValue = jsonGet(drawingController.getScalarValue)

  val demoModeController = new DemoModeController(BigGraphProductionEnvironment)
  def demoModeStatus = jsonGet(demoModeController.demoModeStatus)
  def enterDemoMode = jsonGet(demoModeController.enterDemoMode)
  def exitDemoMode = jsonGet(demoModeController.exitDemoMode)
}

// Throw FlyingResult anywhere to generate non-200 HTTP responses.
class FlyingResult(val result: mvc.SimpleResult) extends Exception(result.toString)
