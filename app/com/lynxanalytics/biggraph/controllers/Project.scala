package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util.Timestamp
import scala.util.{ Failure, Success, Try }
import scala.reflect.runtime.universe._
import securesocial.{ core => ss }

class Project(val projectName: String)(implicit manager: MetaGraphManager) {
  override def toString = projectName
  val separator = "|"
  assert(!projectName.contains(separator), s"Invalid project name: $projectName")
  val path: SymbolPath = s"projects/$projectName"
  def toFE: FEProject = {
    Try(unsafeToFE) match {
      case Success(fe) => fe
      case Failure(ex) => FEProject(
        name = projectName,
        error = ex.getMessage
      )
    }
  }

  // May raise an exception.
  private def unsafeToFE: FEProject = {
    assert(manager.tagExists(path / "notes"), s"No such project: $projectName")
    val vs = Option(vertexSet).map(_.gUID.toString).getOrElse("")
    val eb = Option(edgeBundle).map(_.gUID.toString).getOrElse("")
    def feAttr[T](e: TypedEntity[T], name: String, isInternal: Boolean = false) = {
      val canBucket = Seq(typeOf[Double], typeOf[String]).exists(e.typeTag.tpe <:< _)
      val canFilter = Seq(typeOf[Double], typeOf[String], typeOf[Long], typeOf[Vector[Any]])
        .exists(e.typeTag.tpe <:< _)
      val isNumeric = Seq(typeOf[Double]).exists(e.typeTag.tpe <:< _)
      FEAttribute(e.gUID.toString, name, e.typeTag.tpe.toString, canBucket, canFilter, isNumeric, isInternal)
    }
    def feList(things: Iterable[(String, TypedEntity[_])]) = {
      things.map { case (name, e) => feAttr(e, name) }.toList
    }
    val members = if (isSegmentation) {
      Some(feAttr(asSegmentation.membersAttribute, "$members", isInternal = true))
    } else {
      None
    }

    FEProject(
      name = projectName,
      undoOp = lastOperation,
      redoOp = nextOperation,
      readACL = readACL,
      writeACL = writeACL,
      vertexSet = vs,
      edgeBundle = eb,
      notes = notes,
      scalars = feList(scalars),
      vertexAttributes = feList(vertexAttributes) ++ members,
      edgeAttributes = feList(edgeAttributes),
      segmentations = segmentations.map(_.toFE).toList)
  }

  private def checkpoints: Seq[String] = get("checkpoints") match {
    case "" => Seq()
    case x => x.split(java.util.regex.Pattern.quote(separator), -1)
  }
  private def checkpoints_=(cs: Seq[String]): Unit = set("checkpoints", cs.mkString(separator))
  private def checkpointIndex = get("checkpointIndex") match {
    case "" => 0
    case x => x.toInt
  }
  private def checkpointIndex_=(x: Int): Unit = set("checkpointIndex", x.toString)

  private def lastOperation = get("lastOperation")
  private def lastOperation_=(x: String): Unit = set("lastOperation", x)
  private def nextOperation = manager.synchronized {
    val i = checkpointIndex + 1
    if (checkpoints.size <= i) ""
    else manager.getTag(s"${checkpoints(i)}/lastOperation")
  }

  def checkpointAfter(op: String): Unit = manager.synchronized {
    if (isSegmentation) {
      val name = asSegmentation.name
      asSegmentation.parent.checkpointAfter(s"$op on $name")
    } else {
      lastOperation = op
      val nextIndex = if (checkpoints.nonEmpty) checkpointIndex + 1 else 0
      val timestamp = Timestamp.toString
      val checkpoint = s"checkpoints/$path/$timestamp"
      checkpoints = checkpoints.take(nextIndex) :+ checkpoint
      checkpointIndex = nextIndex
      cp(path, checkpoint)
    }
  }

  def checkpoint(title: String)(op: => Unit): Unit = {
    Try(op) match {
      case Success(_) =>
        // Save changes.
        checkpointAfter(title)
      case Failure(e) =>
        // Discard potentially corrupt changes.
        reloadCurrentCheckpoint()
        throw e;
    }
  }

  def undo(): Unit = manager.synchronized {
    // checkpoints and checkpointIndex are not restored, but copied over from the current state.
    val c = checkpoints
    val i = checkpointIndex
    assert(i > 0, s"Already at checkpoint $i.")
    retaining("readACL", "writeACL") {
      cp(c(i - 1), path)
    }
    checkpointIndex = i - 1
    checkpoints = c
  }
  def redo(): Unit = manager.synchronized {
    // checkpoints and checkpointIndex are not restored, but copied over from the current state.
    val c = checkpoints
    val i = checkpointIndex
    assert(i < c.size - 1, s"Already at checkpoint $i of ${c.size}.")
    retaining("readACL", "writeACL") {
      cp(c(i + 1), path)
    }
    checkpointIndex = i + 1
    checkpoints = c
  }
  def reloadCurrentCheckpoint(): Unit = manager.synchronized {
    if (isSegmentation) {
      val name = asSegmentation.name
      asSegmentation.parent.reloadCurrentCheckpoint()
    } else {
      // checkpoints and checkpointIndex are not restored, but copied over from the current state.
      val c = checkpoints
      val i = checkpointIndex
      assert(c.nonEmpty, "No checkpoints.")
      retaining("readACL", "writeACL") {
        cp(c(i), path)
      }
      checkpointIndex = i
      checkpoints = c
    }
  }

  // Saves the listed tags before running "op" and restores them after.
  private def retaining(tags: String*)(op: => Unit) = manager.synchronized {
    for (tag <- tags) {
      cp(path / tag, s"tmp/$tag")
    }
    try {
      op
    } finally {
      for (tag <- tags) {
        cp(s"tmp/$tag", path / tag)
        manager.rmTag(s"tmp/$tag")
      }
    }
  }

  def discardCheckpoints(): Unit = manager.synchronized {
    existing(path / "checkpoints").foreach(manager.rmTag(_))
    existing(path / "checkpointIndex").foreach(manager.rmTag(_))
    existing(path / "lastOperation").foreach(manager.rmTag(_))
  }

  def readACL = get("readACL")
  def readACL_=(x: String): Unit = set("readACL", x)
  def writeACL = get("writeACL")
  def writeACL_=(x: String): Unit = set("writeACL", x)
  def assertReadAllowedFrom(user: ss.Identity): Unit = {
    assert(readAllowedFrom(user), s"User ${user.email} does not have read access to project $projectName.")
  }
  def assertWriteAllowedFrom(user: ss.Identity): Unit = {
    assert(writeAllowedFrom(user), s"User ${user.email} does not have write access to project $projectName.")
  }
  def readAllowedFrom(user: ss.Identity): Boolean = {
    // Write access also implies read access.
    writeAllowedFrom(user) || aclContains(readACL, user)
  }
  def writeAllowedFrom(user: ss.Identity): Boolean = {
    aclContains(writeACL, user)
  }

  def aclContains(acl: String, user: ss.Identity): Boolean = {
    // The ACL is a comma-separated list of email addresses with '*' used as a wildcard.
    // We translate this to a regex for checking.
    val regex = acl.replace(".", "\\.").replace(",", "|").replace("*", ".*")
    user.email.get.matches(regex)
  }

  def isSegmentation = manager.synchronized {
    val grandFather = path.parent.parent
    grandFather.nonEmpty && (grandFather.name == 'segmentations)
  }
  def asSegmentation = manager.synchronized {
    assert(isSegmentation, s"$projectName is not a segmentation")
    // If our parent is a top-level project, path is like:
    //   project/parentName/segmentations/segmentationName/project
    val parentName = new SymbolPath(path.drop(1).dropRight(3))
    val segmentationName = path.dropRight(1).last.name
    Segmentation(parentName.toString, segmentationName)
  }

  def notes = get("notes")
  def notes_=(n: String) = set("notes", n)

  def vertexSet = manager.synchronized {
    existing(path / "vertexSet")
      .flatMap(vsPath =>
        Project.withErrorLogging(s"Couldn't resolve vertex set of project $this") {
          manager.vertexSet(vsPath)
        })
      .getOrElse(null)
  }
  def vertexSet_=(e: VertexSet): Unit = {
    updateVertexSet(e, killSegmentations = true)
  }

  def setVertexSet(e: VertexSet, idAttr: String): Unit = manager.synchronized {
    vertexSet = e
    vertexAttributes(idAttr) = graph_operations.IdAsAttribute.run(e)
  }

  private def updateVertexSet(e: VertexSet, killSegmentations: Boolean) = manager.synchronized {
    if (e != vertexSet) {
      // TODO: "Induce" the edges and attributes to the new vertex set.
      edgeBundle = null
      vertexAttributes = Map()
      if (killSegmentations) segmentations.foreach(_.remove())
    }
    set("vertexSet", e)
    if (e != null) {
      val op = graph_operations.CountVertices()
      scalars("vertex_count") = op(op.vertices, e).result.count
    } else {
      scalars("vertex_count") = null
    }
  }

  def pullBackEdgesWithInjection(injection: EdgeBundle): Unit = manager.synchronized {
    val op = graph_operations.PulledOverEdges()
    val newEB = op(op.originalEB, edgeBundle)(op.injection, injection).result.pulledEB
    pullBackEdgesWithInjection(edgeBundle, edgeAttributes.toIndexedSeq, newEB, injection)
  }
  def pullBackEdgesWithInjection(
    origEdgeBundle: EdgeBundle,
    origEAttrs: Seq[(String, Attribute[_])],
    newEdgeBundle: EdgeBundle,
    injection: EdgeBundle): Unit = manager.synchronized {

    assert(injection.properties.compliesWith(EdgeBundleProperties.injection),
      s"Not an injection: $injection")
    assert(injection.srcVertexSet.gUID == newEdgeBundle.asVertexSet.gUID,
      s"Wrong source: $injection")
    assert(injection.dstVertexSet.gUID == origEdgeBundle.asVertexSet.gUID,
      s"Wrong destination: $injection")

    edgeBundle = newEdgeBundle

    origEAttrs.foreach {
      case (name, attr) =>
        edgeAttributes(name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(attr, injection)
    }
  }

  def pullBackWithInjection(injection: EdgeBundle): Unit = manager.synchronized {
    assert(injection.properties.compliesWith(EdgeBundleProperties.injection),
      s"Not an injection: $injection")
    assert(injection.dstVertexSet.gUID == vertexSet.gUID,
      s"Wrong destination: $injection")
    val origVS = vertexSet
    val origVAttrs = vertexAttributes.toIndexedSeq
    val origEB = edgeBundle
    val origEAttrs = edgeAttributes.toIndexedSeq

    updateVertexSet(injection.srcVertexSet, killSegmentations = false)
    origVAttrs.foreach {
      case (name, attr) =>
        vertexAttributes(name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(attr, injection)
    }

    if (origEB != null) {
      val iop = graph_operations.InducedEdgeBundle()
      val induction = iop(
        iop.srcMapping, graph_operations.ReverseEdges.run(injection))(
          iop.dstMapping, graph_operations.ReverseEdges.run(injection))(
            iop.edges, origEB).result
      pullBackEdgesWithInjection(origEB, origEAttrs, induction.induced, induction.embedding)
    }

    segmentations.foreach { seg =>
      val op = graph_operations.InducedEdgeBundle(induceDst = false)
      seg.belongsTo = op(
        op.srcMapping, graph_operations.ReverseEdges.run(injection))(
          op.edges, seg.belongsTo).result.induced
    }

    if (isSegmentation) {
      val seg = asSegmentation
      val op = graph_operations.InducedEdgeBundle(induceSrc = false)
      seg.belongsTo = op(
        op.dstMapping, graph_operations.ReverseEdges.run(injection))(
          op.edges, seg.belongsTo).result.induced
    }
  }

  def edgeBundle = manager.synchronized {
    existing(path / "edgeBundle")
      .flatMap(ebPath =>
        Project.withErrorLogging(s"Couldn't resolve edge bundle of project $this") {
          manager.edgeBundle(ebPath)
        })
      .getOrElse(null)
  }
  def edgeBundle_=(e: EdgeBundle) = manager.synchronized {
    if (e != edgeBundle) {
      assert(e == null || vertexSet != null, s"No vertex set for project $projectName")
      assert(e == null || e.srcVertexSet == vertexSet, s"Edge bundle does not match vertex set for project $projectName")
      assert(e == null || e.dstVertexSet == vertexSet, s"Edge bundle does not match vertex set for project $projectName")
      // TODO: "Induce" the attributes to the new edge bundle.
      edgeAttributes = Map()
    }
    set("edgeBundle", e)
    if (e != null) {
      val op = graph_operations.CountEdges()
      scalars("edge_count") = op(op.edges, e).result.count
    } else {
      scalars("edge_count") = null
    }
  }

  def scalars = new ScalarHolder
  def scalars_=(scalars: Map[String, Scalar[_]]) = manager.synchronized {
    existing(path / "scalars").foreach(manager.rmTag(_))
    for ((name, scalar) <- scalars) {
      manager.setTag(path / "scalars" / name, scalar)
    }
  }
  def scalarNames[T: TypeTag] = scalars.collect {
    case (name, scalar) if typeOf[T] =:= typeOf[Nothing] || scalar.is[T] => name
  }.toSeq

  def vertexAttributes = new VertexAttributeHolder
  def vertexAttributes_=(attrs: Map[String, Attribute[_]]) = manager.synchronized {
    existing(path / "vertexAttributes").foreach(manager.rmTag(_))
    assert(attrs.isEmpty || vertexSet != null, s"No vertex set for project $projectName")
    for ((name, attr) <- attrs) {
      assert(attr.vertexSet == vertexSet, s"Vertex attribute $name does not match vertex set for project $projectName")
      manager.setTag(path / "vertexAttributes" / name, attr)
    }
  }
  def vertexAttributeNames[T: TypeTag] = vertexAttributes.collect {
    case (name, attr) if typeOf[T] =:= typeOf[Nothing] || attr.is[T] => name
  }.toSeq

  def edgeAttributes = new EdgeAttributeHolder
  def edgeAttributes_=(attrs: Map[String, Attribute[_]]) = manager.synchronized {
    existing(path / "edgeAttributes").foreach(manager.rmTag(_))
    assert(attrs.isEmpty || edgeBundle != null, s"No edge bundle for project $projectName")
    for ((name, attr) <- attrs) {
      assert(attr.vertexSet == edgeBundle.asVertexSet, s"Edge attribute $name does not match edge bundle for project $projectName")
      manager.setTag(path / "edgeAttributes" / name, attr)
    }
  }
  def edgeAttributeNames[T: TypeTag] = edgeAttributes.collect {
    case (name, attr) if typeOf[T] =:= typeOf[Nothing] || attr.is[T] => name
  }.toSeq

  def segmentations = segmentationNames.map(segmentation(_))
  def segmentation(name: String) = Segmentation(projectName, name)
  def segmentationNames = ls("segmentations").map(_.last.name)

  def copy(to: Project): Unit = cp(path, to.path)
  def remove(): Unit = manager.synchronized {
    manager.rmTag(path)
    log.info(s"A project has been discarded: $path")
  }

  private def cp(from: SymbolPath, to: SymbolPath) = manager.synchronized {
    existing(to).foreach(manager.rmTag(_))
    manager.cpTag(from, to)
  }

  private def existing(tag: SymbolPath): Option[SymbolPath] =
    if (manager.tagExists(tag)) Some(tag) else None
  private def set(tag: String, entity: MetaGraphEntity): Unit = manager.synchronized {
    if (entity == null) {
      existing(path / tag).foreach(manager.rmTag(_))
    } else {
      manager.setTag(path / tag, entity)
    }
  }
  private def set(tag: String, content: String): Unit = manager.setTag(path / tag, content)
  private def get(tag: String): String = manager.synchronized {
    existing(path / tag).map(manager.getTag(_)).getOrElse("")
  }
  private def ls(dir: String) = manager.synchronized {
    if (manager.tagExists(path / dir)) manager.lsTag(path / dir) else Nil
  }

  abstract class Holder[T <: MetaGraphEntity](dir: String) extends Iterable[(String, T)] {
    def validate(name: String, entity: T): Unit
    def update(name: String, entity: T) = manager.synchronized {
      if (entity == null) {
        existing(path / dir / name).foreach(manager.rmTag(_))
      } else {
        validate(name, entity)
        manager.setTag(path / dir / name, entity)
      }
    }
    def apply(name: String): T =
      manager.entity(path / dir / name).asInstanceOf[T]

    def iterator = manager.synchronized {
      ls(dir)
        .flatMap { path =>
          val name = path.last.name
          Project.withErrorLogging(s"Couldn't resolve $path") { apply(name) }
            .map(name -> _)
        }
        .iterator
    }

    def contains(x: String) = iterator.exists(_._1 == x)
  }
  class ScalarHolder extends Holder[Scalar[_]]("scalars") {
    def validate(name: String, scalar: Scalar[_]) = {}
  }
  class VertexAttributeHolder extends Holder[Attribute[_]]("vertexAttributes") {
    def validate(name: String, attr: Attribute[_]) =
      assert(attr.vertexSet == vertexSet, s"Vertex attribute $name does not match vertex set for project $projectName")
  }
  class EdgeAttributeHolder extends Holder[Attribute[_]]("edgeAttributes") {
    def validate(name: String, attr: Attribute[_]) =
      assert(attr.vertexSet == edgeBundle.asVertexSet, s"Edge attribute $name does not match edge bundle for project $projectName")
  }
}

object Project {
  def apply(projectName: String)(implicit metaManager: MetaGraphManager): Project = new Project(projectName)

  def withErrorLogging[T](message: String)(op: => T): Option[T] =
    try {
      Some(op)
    } catch {
      case e: Throwable => {
        log.error(message, e)
        None
      }
    }
}

case class Segmentation(parentName: String, name: String)(implicit manager: MetaGraphManager) {
  def parent = Project(parentName)
  val path: SymbolPath = s"projects/$parentName/segmentations/$name"
  def toFE = {
    val bt = Option(belongsTo).map(UIValue.fromEntity(_)).getOrElse(null)
    val bta = Option(belongsToAttribute).map(_.gUID.toString).getOrElse("")
    FESegmentation(
      name,
      project.projectName,
      bt,
      UIValue(id = bta, title = "segmentation[%s]".format(name)))
  }
  def belongsTo = {
    Project.withErrorLogging(s"Cannot get 'belongsTo' for $this") {
      manager.edgeBundle(path / "belongsTo")
    }.getOrElse(null)
  }
  def belongsTo_=(eb: EdgeBundle) = manager.synchronized {
    assert(eb.dstVertexSet == project.vertexSet, s"Incorrect 'belongsTo' relationship for $name")
    manager.setTag(path / "belongsTo", eb)
  }
  def belongsToAttribute: Attribute[Vector[ID]] = {
    val segmentationIds = graph_operations.IdAsAttribute.run(project.vertexSet)
    val reversedBelongsTo = graph_operations.ReverseEdges.run(belongsTo)
    val aop = graph_operations.AggregateByEdgeBundle(graph_operations.Aggregator.AsVector[ID]())
    Project.withErrorLogging(s"Cannot get 'belongsToAttribute' for $this") {
      aop(aop.connection, reversedBelongsTo)(aop.attr, segmentationIds).result.attr: Attribute[Vector[ID]]
    }.getOrElse(null)
  }
  // In case the project is a segmentation
  // a Vector[ID] vertex attribute, that contains for each vertex
  // the vector of parent ids the segment contains.
  def membersAttribute: Attribute[Vector[ID]] = {
    val parentIds = graph_operations.IdAsAttribute.run(parent.vertexSet)
    val aop = graph_operations.AggregateByEdgeBundle(graph_operations.Aggregator.AsVector[ID]())
    Project.withErrorLogging(s"Cannot get 'membersAttribute' for $this") {
      aop(aop.connection, belongsTo)(aop.attr, parentIds).result.attr: Attribute[Vector[ID]]
    }.getOrElse(null)
  }
  def project = Project(s"$parentName/segmentations/$name/project")

  def rename(newName: String) = manager.synchronized {
    val to = new SymbolPath(path.init) / newName
    manager.cpTag(path, to)
    manager.rmTag(path)
  }
  def remove(): Unit = manager.synchronized {
    manager.rmTag(path)
  }
}
