// The code for providing a nice interface to Groovy.

package com.lynxanalytics.biggraph.groovy

import groovy.lang.{ Binding, GroovyShell }
import org.apache.spark
import org.kohsuke.groovy.sandbox
import play.api.libs.json
import scala.collection.JavaConversions

import com.lynxanalytics.biggraph
import com.lynxanalytics.biggraph.controllers._
import com.lynxanalytics.biggraph.frontend_operations.Operations
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.serving
import com.lynxanalytics.biggraph.table

object GroovyContext {
  def runScript(scriptFileName: String, params: (String, String)*): Unit = {
    val env = biggraph.BigGraphProductionEnvironment
    val ops = new Operations(env)
    val user = serving.User("Batch User", isAdmin = true)
    val formattedParams = params.map { case (name, value) => s"$name: $value" }.mkString(" ")
    val commandLine = s"$scriptFileName $formattedParams"
    val ctx = GroovyContext(user, ops, Some(env), Some(commandLine))
    val shell = ctx.trustedShell("params" -> JavaConversions.mapAsJavaMap(params.toMap))
    shell.evaluate(new java.io.File(scriptFileName))
    env.dataManager.waitAllFutures() // Finish any writes before shutting down.
  }
}
case class GroovyContext(
    user: biggraph.serving.User,
    ops: OperationRepository,
    env: Option[biggraph.BigGraphEnvironment] = None,
    commandLine: Option[String] = None) {

  implicit lazy val metaManager = env.get.metaGraphManager
  implicit lazy val dataManager = env.get.dataManager
  // Every Groovy script execution should have its own SQLContext for isolation.
  lazy val sqlContext = dataManager.newSQLContext()
  def normalize(name: String) = name.replace("-", "").toLowerCase
  lazy val normalizedIds = ops.operationIds.map(id => normalize(id) -> id).toMap

  def trustedShell(bindings: (String, AnyRef)*) = {
    val binding = new Binding()
    binding.setProperty("lynx", new LynxGroovyInterface(this))
    for ((k, v) <- bindings) {
      binding.setProperty(k, v)
    }
    new GroovyShell(binding)
  }

  // withUntrustedShell has to unregister the thread-local sandbox after evaluations,
  // so the shell is only accessible within a block.
  def withUntrustedShell(bindings: (String, AnyRef)*)(fn: GroovyShell => Unit): Unit = {
    val binding = new Binding()
    for ((k, v) <- bindings) {
      binding.setProperty(k, v)
    }
    val cc = new org.codehaus.groovy.control.CompilerConfiguration()
    cc.addCompilationCustomizers(new sandbox.SandboxTransformer())
    val shell = new GroovyShell(binding, cc)
    val gs = new GroovySandbox(bindings.toMap.keySet)
    gs.register()
    try fn(shell)
    finally gs.unregister()
  }
}

// The sandbox used in withUntrustedShell.
class GroovySandbox(bindings: Set[String]) extends sandbox.GroovyValueFilter {
  override def filter(receiver: AnyRef): AnyRef = {
    // Make all operations not explicitly allowed below fail.
    // (This includes instance creation and setting properties for example.)
    throw new SecurityException(s"Script tried to execute a disallowed operation ($receiver)")
  }

  override def onMethodCall(
    invoker: sandbox.GroovyInterceptor.Invoker,
    receiver: Any, method: String, args: Object*): Object = {
    // Shorthand for "receiver.isInstanceOf[T]".
    def isA[T: reflect.ClassTag] = reflect.classTag[T].runtimeClass.isInstance(receiver)
    // Method calls are only allowed on GroovyWorkflowProject and primitive types.
    if (isA[GroovyWorkflowProject] || isA[String] || isA[Long] || isA[Double] || isA[Int]
      || isA[Boolean]) {
      invoker.call(receiver, method, args: _*)
    } else {
      throw new SecurityException(
        s"Script tried to execute a disallowed operation ($receiver.$method())")
    }
  }

  override def onGetProperty(
    invoker: sandbox.GroovyInterceptor.Invoker,
    receiver: Any, property: String): Object = {
    // The bindings can be accessed on the Script object plus any property on GroovyWorkflowProject.
    if (receiver.isInstanceOf[groovy.lang.Script] && bindings.contains(property) ||
      receiver.isInstanceOf[GroovyWorkflowProject]) {
      invoker.call(receiver, property)
    } else {
      throw new SecurityException(
        s"Script tried to execute a disallowed operation ($property.$property)")
    }
  }

  // This is called for all foo[bar] lookups.
  override def onGetArray(
    invoker: sandbox.GroovyInterceptor.Invoker,
    receiver: Any, index: Any): Object = {
    // Allow map lookup to make segmentations accessible.
    if (receiver.isInstanceOf[java.util.Map[_, _]]) {
      invoker.call(receiver, null, index)
    } else {
      throw new SecurityException(
        s"Script tried to execute a disallowed operation ($receiver[$index])")
    }
  }
}

// This is the interface that is visible from trustedShell as "lynx".
class LynxGroovyInterface(ctx: GroovyContext) {
  def loadProject(name: String): GroovyProject = {
    import ctx.metaManager
    val project = ProjectFrame.fromName(name)
    new GroovyBatchProject(ctx, project.viewer.editor)
  }

  def newProject(): GroovyProject = {
    import ctx.metaManager
    val editor = new RootProjectEditor(RootProjectState.emptyState)
    editor.checkpoint = Some("") // Start with blank checkpoint.
    val project = new GroovyBatchProject(ctx, editor)
    project.applyOperation(
      "Change-project-notes", Map("notes" -> s"Created by batch job: ${ctx.commandLine.get}"))
    project
  }

  def sql(s: String) = ctx.sqlContext.sql(s)

  val sqlContext = ctx.sqlContext

  val drawing = new DrawingGroovyInterface()

  def saveDataFrameAsTable(df: spark.sql.DataFrame, tableName: String, notes: String = ""): Unit = {
    import ctx.metaManager
    import ctx.dataManager
    DirectoryEntry.fromName(tableName).asNewTableFrame(table.TableImport.importDataFrame(df), notes)
  }

}

// This is the interface that is visible from trustedShell as "lynx.drawing". This is
// intended for testing only.
class DrawingGroovyInterface {
  private def toList[T](list: AnyRef): Seq[T] = {
    if (list == null) {
      Seq()
    } else {
      import scala.collection.JavaConverters._
      list.asInstanceOf[java.util.List[T]].asScala
    }
  }

  def newVertexAttributeFilter(params: java.util.Map[String, AnyRef]): FEVertexAttributeFilter = {
    FEVertexAttributeFilter(
      attributeId = params.get("attributeId").asInstanceOf[String],
      valueSpec = params.get("valueSpec").asInstanceOf[String])
  }

  def newVertexDiagramSpec(paramsMap: java.util.Map[String, AnyRef]): VertexDiagramSpec = {
    import scala.collection.JavaConverters._
    val params = paramsMap.asScala.toMap
    VertexDiagramSpec(
      vertexSetId = params("vertexSetId").asInstanceOf[String],
      sampleSmearEdgeBundleId = params
        .getOrElse("sampleSmearEdgeBundleId", "")
        .asInstanceOf[String],
      mode = params("mode")
        .asInstanceOf[String],
      filters = toList[FEVertexAttributeFilter](paramsMap.get("filters")),
      centralVertexIds = params
        .getOrElse("centralVertexIds", Seq())
        .asInstanceOf[Seq[String]],
      attrs = toList[String](paramsMap.get("attrs")),
      xBucketingAttributeId = params
        .getOrElse("xBucketingAttributeId", "")
        .asInstanceOf[String],
      yBucketingAttributeId = params
        .getOrElse("yBucketingAttributeId", "")
        .asInstanceOf[String],
      xNumBuckets = params
        .getOrElse("xNumBuckets", 1)
        .asInstanceOf[Int],
      yNumBuckets = params
        .getOrElse("yNumBuckets", 1)
        .asInstanceOf[Int]
    )
  }

  def newEdgeDiagramSpec(paramsMap: java.util.Map[String, AnyRef]): EdgeDiagramSpec = {
    import scala.collection.JavaConverters._
    val params = paramsMap.asScala.toMap
    val srcIdx = params.getOrElse("srcIdx", "0").asInstanceOf[Int]
    val dstIdx = params.getOrElse("dstIdx", "0").asInstanceOf[Int]
    EdgeDiagramSpec(
      srcDiagramId = s"idx[${srcIdx}]",
      dstDiagramId = s"idx[${dstIdx}]",
      srcIdx = srcIdx,
      dstIdx = dstIdx,
      edgeBundleId = params("edgeBundleId").asInstanceOf[String],
      filters = toList[FEVertexAttributeFilter](paramsMap.get("filters")),
      layout3D = params.getOrElse("layout3D", false).asInstanceOf[Boolean])
  }

  def newFEGraphRequest(paramsMap: java.util.Map[String, AnyRef]): FEGraphRequest = {
    import scala.collection.JavaConverters._
    val params = paramsMap.asScala.toMap
    FEGraphRequest(
      vertexSets = toList(paramsMap.get("vertexSets")),
      edgeBundles = toList(paramsMap.get("edgeBundles"))
    )
  }
}

// The basic interface for running operations against a project.
abstract class GroovyProject(ctx: GroovyContext)
    extends groovy.lang.GroovyObjectSupport {

  private[groovy] def applyOperation(id: String, params: Map[String, String]): Unit
  protected def getSegmentations: Map[String, GroovyProject]

  override def invokeMethod(name: String, args: AnyRef): AnyRef = {
    val argArray = args.asInstanceOf[Array[_]]
    val params: Map[String, String] = if (argArray.nonEmpty) {
      val javaParams = argArray.head.asInstanceOf[java.util.Map[AnyRef, AnyRef]]
      JavaConversions.mapAsScalaMap(javaParams).map {
        case (k, v) => (k.toString, v.toString)
      }.toMap
    } else Map()
    val id = {
      val normalized = ctx.normalize(name)
      assert(ctx.normalizedIds.contains(normalized), s"No such operation: $name")
      ctx.normalizedIds(normalized)
    }
    applyOperation(id, params)
    null
  }

  // Public method for running workflows. (Workflow names are not valid identifiers.)
  // Groovy methods with keyword arguments need to have a Map as their first parameter.
  // Calling this from Groovy is pretty nice:
  //   project.runWorkflow('My Workflow', my_parameter: 44, my_other_parameter: 'no')
  // The workflow name may or may not include the timestamp. If it is missing, the
  // latest version of the workflow is used.
  def runWorkflow(javaParams: java.util.Map[AnyRef, AnyRef], id: String): Unit = {
    val params = JavaConversions.mapAsScalaMap(javaParams).map {
      case (k, v) => (k.toString, v.toString)
    }.toMap
    val tag =
      if (id.contains("/")) s"${BigGraphController.workflowsRoot}/$id"
      else ctx.ops.newestWorkflow(id).toString
    applyOperation(tag, params)
  }
  // Special case with no parameters.
  def runWorkflow(id: String): Unit = runWorkflow(new java.util.HashMap, id)

  override def getProperty(name: String): AnyRef = {
    name match {
      case "segmentations" => JavaConversions.mapAsJavaMap(getSegmentations)
      case _ => getMetaClass().getProperty(this, name)
    }
  }
}

// Batch mode creates checkpoints and gives access to scalars/attributes.
class GroovyBatchProject(ctx: GroovyContext, editor: ProjectEditor)
    extends GroovyProject(ctx) {

  override def getProperty(name: String): AnyRef = {
    implicit val dm = ctx.dataManager
    name match {
      case "scalars" => JavaConversions.mapAsJavaMap(getScalars)
      case "vertexAttributes" => JavaConversions.mapAsJavaMap(getVertexAttributes)
      case "edgeAttributes" => JavaConversions.mapAsJavaMap(getEdgeAttributes)
      case "vertexDF" =>
        Table.fromTableName(Table.VertexTableName, editor.viewer).toDF(ctx.sqlContext)
      case "edgeDF" =>
        Table.fromTableName(Table.EdgeTableName, editor.viewer).toDF(ctx.sqlContext)
      case "belongsToDF" =>
        assert(editor.isSegmentation, "belongsToDF is only defined for segmentations.")
        Table.fromTableName(Table.BelongsToTableName, editor.viewer).toDF(ctx.sqlContext)
      case _ => super.getProperty(name)
    }
  }

  def copy() = {
    assert(!editor.isSegmentation, "You can only create copies of top-level projects.")
    new GroovyBatchProject(ctx, editor.viewer.editor)
  }

  def saveAs(newRootName: String): Unit = {
    assert(!editor.isSegmentation, "You cannot save a segmentation as a top-level project.")
    import ctx.metaManager
    val entry = DirectoryEntry.fromName(newRootName)
    val project = if (!entry.exists) {
      val p = entry.asNewProjectFrame()
      p.writeACL = ctx.user.email
      p.readACL = ctx.user.email
      p
    } else {
      entry.asProjectFrame
    }
    project.setCheckpoint(editor.rootCheckpoint)
  }

  // Creates a string that can be used as the value for a Choice that expects a titled
  // checkpoint. It will point to the checkpoint of the root project of this project.
  def rootCheckpointWithTitle(title: String): String =
    FEOption.titledCheckpoint(editor.rootCheckpoint, title).id

  private[groovy] override def applyOperation(id: String, params: Map[String, String]): Unit = {
    val context = Operation.Context(ctx.user, editor.viewer)
    val spec = FEOperationSpec(id, params)
    editor.rootEditor.rootState = ctx.ops.applyAndCheckpoint(context, spec)
  }

  protected def getScalars: Map[String, GroovyScalar] = {
    editor.scalars.toMap.mapValues(new GroovyScalar(ctx, _))
  }

  protected def getVertexAttributes: Map[String, GroovyAttribute] = {
    editor.vertexAttributes.toMap.mapValues(new GroovyAttribute(ctx, _))
  }

  protected def getEdgeAttributes: Map[String, GroovyAttribute] = {
    editor.edgeAttributes.toMap.mapValues(new GroovyAttribute(ctx, _))
  }

  protected def getSegmentations: Map[String, GroovyProject] = {
    editor.segmentationNames.map { name =>
      name -> new GroovyBatchProject(ctx, editor.segmentation(name))
    }.toMap
  }

  def getVertexSetId(): String =
    editor.vertexSet.gUID.toString

  def getEdgeBundleId(): String =
    editor.edgeBundle.gUID.toString

  def getBelongsToId(): String =
    editor.asSegmentation.belongsTo.gUID.toString

  // Intended for testing only.
  def getCenters(count: Int): Seq[String] = {
    val drawing = new GraphDrawingController(ctx.env.get)
    val req = CenterRequest(
      vertexSetId = editor.vertexSet.gUID.toString,
      count = count,
      filters = Seq())
    val res = drawing.getCenter(ctx.user, req)
    res.centers
  }

  // Intended for testing only.
  def getComplexView(req: FEGraphRequest): FEGraphResponse = {
    val drawing = new GraphDrawingController(ctx.env.get)
    drawing.getComplexView(ctx.user, req)
  }
}

class GroovyScalar(ctx: GroovyContext, scalar: Scalar[_]) {
  override def toString = {
    import ctx.dataManager
    scalar.value.toString
  }
  def toDouble: Double = {
    toString.toDouble
  }
}

class GroovyAttribute(ctx: GroovyContext, attr: Attribute[_]) {
  val id = attr.gUID.toString

  def histogram: String = histogram(10)

  def histogram(numBuckets: Int): String = {
    val drawing = new GraphDrawingController(ctx.env.get)
    val req = HistogramSpec(
      attributeId = attr.gUID.toString,
      vertexFilters = Seq(),
      numBuckets = numBuckets,
      axisOptions = AxisOptions(logarithmic = false),
      sampleSize = 50000)
    val res = drawing.getHistogram(ctx.user, req)
    import com.lynxanalytics.biggraph.serving.FrontendJson._
    json.Json.toJson(res).toString
  }
}

// No checkpointing or entity access in workflow mode.
// This class is exposed to untrusted scripts. Make sure its public API is properly restricted!
class GroovyWorkflowProject(
    ctx: GroovyContext, rootProject: ProjectEditor, path: Seq[String]) extends GroovyProject(ctx) {
  protected def viewer = rootProject.offspringEditor(path).viewer
  private[groovy] override def applyOperation(id: String, params: Map[String, String]): Unit = {
    val opctx = Operation.Context(ctx.user, viewer)
    // Execute the operation.
    val op = ctx.ops.appliedOp(opctx, FEOperationSpec(id, params))
    // Then copy back the state created by the operation. We have to copy at
    // root level, as operations might reach up and modify parent state as well.
    rootProject.state = op.project.rootEditor.state
  }

  override protected def getSegmentations: Map[String, GroovyProject] = {
    viewer.segmentationMap.keys.map { seg =>
      seg -> new GroovyWorkflowProject(ctx, rootProject, path :+ seg)
    }.toMap
  }
}
