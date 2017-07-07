// The controller to receive and dispatch all JSON HTTP requests from the frontend.
package com.lynxanalytics.biggraph.serving

import java.io.{ File, FileOutputStream }

import play.api.libs.json
import play.api.mvc

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import com.lynxanalytics.biggraph.BigGraphProductionEnvironment
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.controllers._
import com.lynxanalytics.biggraph.graph_api.BuiltIns
import com.lynxanalytics.biggraph.graph_operations.DynamicValue
import com.lynxanalytics.biggraph.graph_util.{ Timestamp, LoggedEnvironment, KiteInstanceInfo, HadoopFile }
import com.lynxanalytics.biggraph.protection.Limitations
import com.lynxanalytics.biggraph.model
import com.lynxanalytics.biggraph.serving
import org.apache.spark.sql.types.{ StructField, StructType }

abstract class JsonServer extends mvc.Controller {
  def testMode = play.api.Play.maybeApplication == None
  def productionMode = !testMode && play.api.Play.current.configuration.getString("application.secret").nonEmpty

  // UserController is initialized later but referred in asyncAction().
  def userController: UserController

  def action[A](parser: mvc.BodyParser[A], withAuth: Boolean = productionMode)(
    block: (User, mvc.Request[A]) => mvc.Result): mvc.Action[A] = {

    asyncAction(parser, withAuth) { (user, request) =>
      Future(block(user, request))
    }
  }

  def asyncAction[A](parser: mvc.BodyParser[A], withAuth: Boolean = productionMode)(
    block: (User, mvc.Request[A]) => Future[mvc.Result]): mvc.Action[A] = {
    if (withAuth) {
      // TODO: Redirect HTTP to HTTPS. (#1400)
      mvc.Action.async(parser) { request =>
        userController.get(request) match {
          case Some(user) => block(user, request)
          case None => Future.successful(Unauthorized)
        }
      }
    } else {
      // No authentication in development mode.
      mvc.Action.async(parser) { request => block(User.fake, request) }
    }
  }

  def jsonPostCommon[I: json.Reads, R](
    user: User,
    request: mvc.Request[json.JsValue], logRequest: Boolean = true)(
      handler: (User, I) => R): R = {
    val t0 = System.currentTimeMillis
    if (logRequest) {
      log.info(s"$user POST ${request.path} ${request.body}")
    } else {
      log.info(s"$user POST ${request.path} (request body logging supressed)")
    }
    val i = request.body.as[I]
    val result = util.Try(handler(user, i))
    val dt = System.currentTimeMillis - t0
    val status = if (result.isSuccess) "success" else "failure"
    log.info(s"$dt ms to respond with $status to $user POST ${request.path}")
    result.get
  }

  def jsonPost[I: json.Reads, O: json.Writes](
    handler: (User, I) => O,
    logRequest: Boolean = true) = {
    action(parse.json) { (user, request) =>
      jsonPostCommon(user, request, logRequest) { (user: User, i: I) =>
        Ok(json.Json.toJson(handler(user, i)))
      }
    }
  }

  def jsonFuturePost[I: json.Reads, O: json.Writes](
    handler: (User, I) => Future[O],
    logRequest: Boolean = true) = {
    asyncAction(parse.json) { (user, request) =>
      jsonPostCommon(user, request, logRequest) { (user: User, i: I) =>
        handler(user, i).map(o => Ok(json.Json.toJson(o)))
      }
    }
  }

  private def parseJson[T: json.Reads](user: User, request: mvc.Request[mvc.AnyContent]): T = {
    // We do our own simple parsing instead of using request.getQueryString due to #2507.
    val qs = request.rawQueryString
    assert(qs.startsWith("q="), "Missing query parameter: q")
    val s = java.net.URLDecoder.decode(qs.drop(2), "utf8")
    log.info(s"$user GET ${request.path} $s")
    json.Json.parse(s).as[T]
  }

  def jsonQuery[I: json.Reads, R](
    user: User,
    request: mvc.Request[mvc.AnyContent])(handler: (User, I) => R): R = {
    val t0 = System.currentTimeMillis
    val result = util.Try(handler(user, parseJson(user, request)))
    val dt = System.currentTimeMillis - t0
    val status = if (result.isSuccess) "success" else "failure"
    log.info(s"$dt ms to respond with $status to $user GET ${request.path}")
    result.get
  }

  def jsonGet[I: json.Reads, O: json.Writes](handler: (User, I) => O) = {
    action(parse.anyContent) { (user, request) =>
      assert(request.headers.get("X-Requested-With") == Some("XMLHttpRequest"),
        "Rejecting request because 'X-Requested-With: XMLHttpRequest' header is missing.")
      jsonQuery(user, request) { (user: User, i: I) =>
        Ok(json.Json.toJson(handler(user, i)))
      }
    }
  }

  // An non-authenticated, no input GET request that returns JSON.
  def jsonPublicGet[O: json.Writes](handler: => O) = {
    action(parse.anyContent, withAuth = false) { (user, request) =>
      log.info(s"GET ${request.path}")
      Ok(json.Json.toJson(handler))
    }
  }

  def jsonFuture[I: json.Reads, O: json.Writes](handler: (User, I) => Future[O]) = {
    asyncAction(parse.anyContent) { (user, request) =>
      assert(request.headers.get("X-Requested-With") == Some("XMLHttpRequest"),
        "Rejecting request because 'X-Requested-With: XMLHttpRequest' header is missing.")
      jsonQuery(user, request) { (user: User, i: I) =>
        handler(user, i).map(o => Ok(json.Json.toJson(o)))
      }
    }
  }

  def healthCheck(checkHealthy: () => Unit) = mvc.Action { request =>
    log.info(s"GET ${request.path}")
    checkHealthy()
    Ok("Server healthy")
  }
}

case class Empty()

case class AuthMethod(id: String, name: String)

case class GlobalSettings(
  hasAuth: Boolean,
  authMethods: List[AuthMethod],
  title: String,
  tagline: String,
  workspaceParameterKinds: List[String],
  version: String)

object AssertLicenseNotExpired {
  def apply() = {
    if (Limitations.isExpired()) {
      val message = "Your licence has expired, please contact Lynx Analytics for a new licence."
      println(message)
      log.error(message)
      throw new RuntimeException(message)
    }
  }
}

object AssertNotRunningAndRegisterRunning {
  private def getPidFile(pidFilePath: String): File = {
    val pidFile = new File(pidFilePath).getAbsoluteFile
    if (pidFile.exists) {
      throw new RuntimeException(s"LynxKite is already running (or delete $pidFilePath)")
    }
    pidFile
  }

  private def getPid(): String = {

    val pidAtHostname =
      // Returns <pid>@<hostname>
      java.lang.management.ManagementFactory.getRuntimeMXBean.getName
    val atSignIndex = pidAtHostname.indexOf('@')
    pidAtHostname.take(atSignIndex)
  }

  private def writePid(pidFile: File) = {
    val pid = getPid()
    val output = new FileOutputStream(pidFile)
    try output.write(pid.getBytes) finally output.close()
  }

  def apply() = {
    val pidFilePath = LoggedEnvironment.envOrNone("KITE_PID_FILE")
    if (pidFilePath.isDefined) {
      val pidFile = getPidFile(pidFilePath.get)
      writePid(pidFile)
      pidFile.deleteOnExit()
    }
  }
}

object FrontendJson {
  /**
   * Implicit JSON inception
   *
   * json.Json.toJson needs one for every incepted case class,
   * they need to be ordered so that everything is declared before use.
   */
  import model.FEModel
  import model.FEModelMeta

  implicit val rEmpty = new json.Reads[Empty] {
    def reads(j: json.JsValue) = json.JsSuccess(Empty())
  }
  implicit val wUnit = new json.Writes[Unit] {
    def writes(u: Unit) = json.Json.obj()
  }
  implicit val wStructType = new json.Writes[StructType] {
    def writes(structType: StructType): json.JsValue = json.Json.obj("schema" -> structType.map {
      case StructField(name, dataType: StructType, nullable, metaData) =>
        json.Json.obj("name" -> name, "dataType" -> this.writes(dataType), "nullable" -> nullable)
      case StructField(name, dataType, nullable, metaData) =>
        json.Json.obj("name" -> name, "dataType" -> dataType.toString(), "nullable" -> nullable)
    })
  }
  implicit val fDownloadFileRequest = json.Json.format[DownloadFileRequest]

  implicit val fFEStatus = json.Json.format[FEStatus]
  implicit val fFEOption = json.Json.format[FEOption]
  implicit val wFEOperationParameterMeta = json.Json.writes[FEOperationParameterMeta]
  implicit val fCustomOperationParameterMeta = json.Json.format[CustomOperationParameterMeta]
  implicit val wDynamicValue = json.Json.writes[DynamicValue]
  implicit val wFEScalar = json.Json.writes[FEScalar]
  implicit val wFEOperationMeta = json.Json.writes[FEOperationMeta]

  implicit val rFEOperationSpec = json.Json.reads[FEOperationSpec]

  implicit val rSparkStatusRequest = json.Json.reads[SparkStatusRequest]
  implicit val wStageInfo = json.Json.writes[StageInfo]
  implicit val wSparkStatusResponse = json.Json.writes[SparkStatusResponse]

  implicit val rFEVertexAttributeFilter = json.Json.reads[FEVertexAttributeFilter]
  implicit val rAxisOptions = json.Json.reads[AxisOptions]
  implicit val rVertexDiagramSpec = json.Json.reads[VertexDiagramSpec]
  implicit val wFEModel = json.Json.writes[FEModel]
  implicit val wFEModelMeta = json.Json.writes[FEModelMeta]
  implicit val wFEVertex = json.Json.writes[FEVertex]
  implicit val wVertexDiagramResponse = json.Json.writes[VertexDiagramResponse]

  implicit val rBundleSequenceStep = json.Json.reads[BundleSequenceStep]
  implicit val rAggregatedAttribute = json.Json.reads[AggregatedAttribute]
  implicit val rEdgeDiagramSpec = json.Json.reads[EdgeDiagramSpec]
  implicit val wFE3DPosition = json.Json.writes[FE3DPosition]
  implicit val wFEEdge = json.Json.writes[FEEdge]
  implicit val wEdgeDiagramResponse = json.Json.writes[EdgeDiagramResponse]

  implicit val rFEGraphRequest = json.Json.reads[FEGraphRequest]
  implicit val wFEGraphResponse = json.Json.writes[FEGraphResponse]

  implicit val rCenterRequest = json.Json.reads[CenterRequest]
  implicit val wCenterResponse = json.Json.writes[CenterResponse]

  implicit val rHistogramSpec = json.Json.reads[HistogramSpec]
  implicit val wHistogramResponse = json.Json.writes[HistogramResponse]

  implicit val rScalarValueRequest = json.Json.reads[ScalarValueRequest]

  implicit val rCreateDirectoryRequest = json.Json.reads[CreateDirectoryRequest]
  implicit val rDiscardEntryRequest = json.Json.reads[DiscardEntryRequest]
  implicit val rRenameEntryRequest = json.Json.reads[RenameEntryRequest]
  implicit val rProjectOperationRequest = json.Json.reads[ProjectOperationRequest]
  implicit val rSubProjectOperation = json.Json.reads[SubProjectOperation]
  implicit val rProjectAttributeFilter = json.Json.reads[ProjectAttributeFilter]
  implicit val rForkEntryRequest = json.Json.reads[ForkEntryRequest]
  implicit val rACLSettingsRequest = json.Json.reads[ACLSettingsRequest]
  implicit val rEntryListRequest = json.Json.reads[EntryListRequest]
  implicit val rEntrySearchRequest = json.Json.reads[EntrySearchRequest]
  implicit val wFEOperationCategory = json.Json.writes[FEOperationCategory]
  implicit val wFEAttribute = json.Json.writes[FEAttribute]
  implicit val wFESegmentation = json.Json.writes[FESegmentation]
  implicit val wFEProject = json.Json.writes[FEProject]
  implicit val wFEEntryListElement = json.Json.writes[FEEntryListElement]
  implicit val wEntryList = json.Json.writes[EntryList]
  implicit val wFEOperationSpec = json.Json.writes[FEOperationSpec]
  implicit val wSubProjectOperation = json.Json.writes[SubProjectOperation]

  import WorkspaceJsonFormatters._
  implicit val fBoxOutputInfo = json.Json.format[BoxOutputInfo]
  implicit val fProgress = json.Json.format[Progress]
  implicit val rWorkspaceReference = json.Json.reads[WorkspaceReference]
  implicit val wGetWorkspaceResponse = json.Json.writes[GetWorkspaceResponse]
  implicit val rSetWorkspaceRequest = json.Json.reads[SetWorkspaceRequest]
  implicit val rGetOperationMetaRequest = json.Json.reads[GetOperationMetaRequest]
  implicit val rGetProgressRequest = json.Json.reads[GetProgressRequest]
  implicit val rGetProgressResponse = json.Json.writes[GetProgressResponse]
  implicit val rGetProjectOutputRequest = json.Json.reads[GetProjectOutputRequest]
  implicit val rGetTableOutputRequest = json.Json.reads[GetTableOutputRequest]
  implicit val wTableColumn = json.Json.writes[TableColumn]
  implicit val wGetTableOutputResponse = json.Json.writes[GetTableOutputResponse]
  implicit val rGetPlotOutputRequest = json.Json.reads[GetPlotOutputRequest]
  implicit val wGetPlotOutputResponse = json.Json.writes[GetPlotOutputResponse]
  implicit val rGetVisualizationOutputRequest = json.Json.reads[GetVisualizationOutputRequest]
  implicit val rCreateWorkspaceRequest = json.Json.reads[CreateWorkspaceRequest]
  implicit val wBoxCatalogResponse = json.Json.writes[BoxCatalogResponse]
  implicit val rCreateSnapshotRequest = json.Json.reads[CreateSnapshotRequest]
  implicit val rGetExportResultRequest = json.Json.reads[GetExportResultRequest]
  implicit val wGetExportResultResponse = json.Json.writes[GetExportResultResponse]

  implicit val fDataFrameSpec = json.Json.format[DataFrameSpec]
  implicit val fSQLCreateView = json.Json.format[SQLCreateViewRequest]
  implicit val rSQLTableBrowserNodeRequest = json.Json.reads[TableBrowserNodeRequest]
  implicit val rTableBrowserNodeForBoxRequest = json.Json.reads[TableBrowserNodeForBoxRequest]
  implicit val rSQLQueryRequest = json.Json.reads[SQLQueryRequest]
  implicit val fSQLExportToTableRequest = json.Json.format[SQLExportToTableRequest]
  implicit val rSQLExportToCSVRequest = json.Json.reads[SQLExportToCSVRequest]
  implicit val rSQLExportToJsonRequest = json.Json.reads[SQLExportToJsonRequest]
  implicit val rSQLExportToParquetRequest = json.Json.reads[SQLExportToParquetRequest]
  implicit val rSQLExportToORCRequest = json.Json.reads[SQLExportToORCRequest]
  implicit val rSQLExportToJdbcRequest = json.Json.reads[SQLExportToJdbcRequest]
  implicit val wTableDesc = json.Json.writes[TableBrowserNode]
  implicit val wSQLTableBrowserNodeResponse = json.Json.writes[TableBrowserNodeResponse]
  implicit val wSQLColumn = json.Json.writes[SQLColumn]
  implicit val wSQLQueryResult = json.Json.writes[SQLQueryResult]
  implicit val wSQLExportToFileResult = json.Json.writes[SQLExportToFileResult]

  implicit val wDemoModeStatusResponse = json.Json.writes[DemoModeStatusResponse]

  implicit val rChangeUserPasswordRequest = json.Json.reads[ChangeUserPasswordRequest]
  implicit val rChangeUserRequest = json.Json.reads[ChangeUserRequest]
  implicit val rDeleteUserRequest = json.Json.reads[DeleteUserRequest]
  implicit val rCreateUserRequest = json.Json.reads[CreateUserRequest]
  implicit val wFEUser = json.Json.writes[FEUser]
  implicit val wFEUserList = json.Json.writes[FEUserList]

  implicit val wAuthMethod = json.Json.writes[AuthMethod]
  implicit val wGlobalSettings = json.Json.writes[GlobalSettings]

  implicit val wFileDescriptor = json.Json.writes[FileDescriptor]
  implicit val wLogFiles = json.Json.writes[LogFiles]
  implicit val rDownloadLogFileRequest = json.Json.reads[DownloadLogFileRequest]

  implicit val rMoveToTrashRequest = json.Json.reads[MoveToTrashRequest]
  implicit val wDataFilesStats = json.Json.writes[DataFilesStats]
  implicit val wDataFilesStatus = json.Json.writes[DataFilesStatus]

  implicit val wBackupSettings = json.Json.writes[BackupSettings]
  implicit val wBackupVersion = json.Json.writes[BackupVersion]

}

object ProductionJsonServer extends JsonServer {
  import FrontendJson._
  import WorkspaceJsonFormatters._

  AssertLicenseNotExpired()
  AssertNotRunningAndRegisterRunning()

  // File upload.
  def upload = {
    action(parse.multipartFormData) { (user, request) =>
      val upload: mvc.MultipartFormData.FilePart[play.api.libs.Files.TemporaryFile] =
        request.body.file("file").get
      try {
        val size = upload.ref.file.length
        log.info(s"upload: $user ${upload.filename} ($size bytes)")
        val dataRepo = BigGraphProductionEnvironment.dataManager.repositoryPath
        val baseName = upload.filename.replace(" ", "_")
        val tmpName = s"$baseName.$Timestamp"
        val tmpFile = dataRepo / "tmp" / tmpName
        val md = java.security.MessageDigest.getInstance("MD5");
        val stream = new java.security.DigestOutputStream(tmpFile.create(), md)
        try java.nio.file.Files.copy(upload.ref.file.toPath, stream)
        finally stream.close()
        val digest = md.digest().map("%02x".format(_)).mkString
        val finalName = s"$digest.$baseName"
        val uploadsDir = HadoopFile("UPLOAD$")
        uploadsDir.mkdirs() // Create the directory if it does not already exist.
        val finalFile = uploadsDir / finalName
        if (finalFile.exists) {
          log.info(s"The uploaded file ($tmpFile) already exists (as $finalFile).")
        } else {
          val success = tmpFile.renameTo(finalFile)
          assert(success, s"Failed to rename $tmpFile to $finalFile.")
        }
        Ok(finalFile.symbolicName)
      } finally upload.ref.clean() // Delete temporary file.
    }
  }

  def oldCSVDownload = action(parse.anyContent)(Downloads.oldCSVDownload)

  def downloadFile = action(parse.anyContent) {
    (user, request) => jsonQuery(user, request)(Downloads.downloadFile)
  }

  def jsError = mvc.Action(parse.json) { request =>
    val url = (request.body \ "url").as[String]
    val stack = (request.body \ "stack").as[String]
    log.info(s"JS error at $url:\n$stack")
    Ok("logged")
  }

  // Methods called by the web framework
  //
  // Play! uses the routings in /conf/routes to execute actions

  val bigGraphController = new BigGraphController(BigGraphProductionEnvironment)
  def createDirectory = jsonPost(bigGraphController.createDirectory)
  def discardEntry = jsonPost(bigGraphController.discardEntry)
  def renameEntry = jsonPost(bigGraphController.renameEntry)
  def discardAll = jsonPost(bigGraphController.discardAll)
  def projectOp = jsonPost(bigGraphController.projectOp)
  def entryList = jsonGet(bigGraphController.entryList)
  def entrySearch = jsonGet(bigGraphController.entrySearch)
  def forkEntry = jsonPost(bigGraphController.forkEntry)
  def changeACLSettings = jsonPost(bigGraphController.changeACLSettings)

  val workspaceController = new WorkspaceController(BigGraphProductionEnvironment)
  def createWorkspace = jsonPost(workspaceController.createWorkspace)
  def getWorkspace = jsonGet(workspaceController.getWorkspace)
  def createSnapshot = jsonPost(workspaceController.createSnapshot)
  def getProjectOutput = jsonGet(workspaceController.getProjectOutput)
  def getProgress = jsonGet(workspaceController.getProgress)
  def getOperationMeta = jsonGet(workspaceController.getOperationMeta)
  def setWorkspace = jsonPost(workspaceController.setWorkspace)
  def undoWorkspace = jsonPost(workspaceController.undoWorkspace)
  def redoWorkspace = jsonPost(workspaceController.redoWorkspace)
  def boxCatalog = jsonGet(workspaceController.boxCatalog)
  def getPlotOutput = jsonGet(workspaceController.getPlotOutput)
  import UIStatusSerialization.fTwoSidedUIStatus
  def getVisualizationOutput = jsonGet(workspaceController.getVisualizationOutput)
  def getExportResultOutput = jsonGet(workspaceController.getExportResultOutput)

  val sqlController = new SQLController(BigGraphProductionEnvironment, workspaceController.ops)
  def getTableBrowserNodes = jsonGet(sqlController.getTableBrowserNodes)
  def runSQLQuery = jsonFuture(sqlController.runSQLQuery)
  def exportSQLQueryToTable = jsonFuturePost(sqlController.exportSQLQueryToTable)
  def exportSQLQueryToCSV = jsonFuturePost(sqlController.exportSQLQueryToCSV)
  def exportSQLQueryToJson = jsonFuturePost(sqlController.exportSQLQueryToJson)
  def exportSQLQueryToParquet = jsonFuturePost(sqlController.exportSQLQueryToParquet)
  def exportSQLQueryToORC = jsonFuturePost(sqlController.exportSQLQueryToORC)
  def exportSQLQueryToJdbc = jsonFuturePost(sqlController.exportSQLQueryToJdbc)
  def importBox = jsonFuturePost(sqlController.importBox)
  def createViewDFSpec = jsonPost(sqlController.createViewDFSpec)

  def getTableOutput = jsonFuture(getTableOutputData)
  def getTableOutputData(user: serving.User, request: GetTableOutputRequest): Future[GetTableOutputResponse] = {
    implicit val metaManager = workspaceController.metaManager
    val table = workspaceController.getOutput(user, request.id).table
    sqlController.getTableSample(table, request.sampleRows)
  }
  def getTableBrowserNodesForBox = jsonGet(getTableBrowserNodesForBoxData)
  def getTableBrowserNodesForBoxData(
    user: serving.User, request: TableBrowserNodeForBoxRequest): TableBrowserNodeResponse = {
    val inputTables = workspaceController.getOperationInputTables(user, request.operationRequest)
    sqlController.getTableBrowserNodesForBox(user, inputTables, request.path)
  }

  val sparkClusterController = new SparkClusterController(BigGraphProductionEnvironment)
  def sparkStatus = jsonFuture(sparkClusterController.sparkStatus)
  def sparkCancelJobs = jsonPost(sparkClusterController.sparkCancelJobs)
  def sparkHealthCheck = healthCheck(sparkClusterController.checkSparkOperational)

  val drawingController = new GraphDrawingController(BigGraphProductionEnvironment)
  def complexView = jsonGet(drawingController.getComplexView)
  def center = jsonFuture(drawingController.getCenter)
  def histo = jsonFuture(drawingController.getHistogram)
  def scalarValue = jsonFuture(drawingController.getScalarValue)
  def model = jsonFuture(drawingController.getModel)

  val demoModeController = new DemoModeController(BigGraphProductionEnvironment)
  def demoModeStatus = jsonGet(demoModeController.demoModeStatus)
  def enterDemoMode = jsonGet(demoModeController.enterDemoMode)
  def exitDemoMode = jsonGet(demoModeController.exitDemoMode)

  val userController = new UserController(BigGraphProductionEnvironment)
  val passwordLogin = userController.passwordLogin
  val googleLogin = userController.googleLogin
  val logout = userController.logout
  def getUsers = jsonGet(userController.getUsers)
  def changeUserPassword = jsonPost(userController.changeUserPassword, logRequest = false)
  def changeUser = jsonPost(userController.changeUser, logRequest = false)
  def deleteUser = jsonPost(userController.deleteUser, logRequest = false)
  def createUser = jsonPost(userController.createUser, logRequest = false)
  def getUserData = jsonGet(userController.getUserData)

  val cleanerController = new CleanerController(BigGraphProductionEnvironment)
  def getDataFilesStatus = jsonGet(cleanerController.getDataFilesStatus)
  def moveToCleanerTrash = jsonPost(cleanerController.moveToCleanerTrash)
  def emptyCleanerTrash = jsonPost(cleanerController.emptyCleanerTrash)

  val logController = new LogController()
  def getLogFiles = jsonGet(logController.getLogFiles)
  def forceLogRotate = jsonPost(logController.forceLogRotate)
  def downloadLogFile = action(parse.anyContent) {
    (user, request) => jsonQuery(user, request)(logController.downloadLogFile)
  }

  val version = KiteInstanceInfo.kiteVersion

  def getAuthMethods = {
    val authMethods = scala.collection.mutable.ListBuffer[AuthMethod]()
    if (productionMode) {
      authMethods += AuthMethod("lynxkite", "LynxKite")
      if (LDAPProps.hasLDAP) { authMethods += AuthMethod("ldap", "LDAP") }
    }
    authMethods.toList
  }

  def getGlobalSettings = jsonPublicGet {
    GlobalSettings(
      hasAuth = productionMode,
      authMethods = getAuthMethods,
      title = LoggedEnvironment.envOrElse("KITE_TITLE", "LynxKite"),
      tagline = LoggedEnvironment.envOrElse("KITE_TAGLINE", "Graph analytics for the brave"),
      workspaceParameterKinds = CustomOperationParameterMeta.validKinds,
      version = version)
  }

  val copyController = new CopyController(BigGraphProductionEnvironment, sparkClusterController)
  def copyEphemeral = jsonPost(copyController.copyEphemeral)
  def getBackupSettings = jsonGet(copyController.getBackupSettings)
  def backup = jsonGet(copyController.backup)

  Ammonite.maybeStart()
  implicit val metaManager = workspaceController.metaManager
  BuiltIns.createBuiltIns(metaManager)
}
