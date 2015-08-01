// Utilities to mark and delete unused data files.

package com.lynxanalytics.biggraph.controllers

import java.util.UUID

import scala.collection.immutable.Set
import scala.collection.immutable.Map
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap

import com.lynxanalytics.biggraph.BigGraphEnvironment
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.serving
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }

case class DataFilesStats(
  id: String = "",
  name: String = "",
  desc: String = "",
  fileCount: Long,
  totalSize: Long)

case class DataFilesStatus(
  total: DataFilesStats,
  methods: List[DataFilesStats])

case class Method(
  id: String,
  name: String,
  desc: String,
  filesToKeep: () => Set[String])

case class MarkDeletedRequest(method: String)

case class AllFiles(
  entities: Map[String, Long],
  operations: Map[String, Long],
  scalars: Map[String, Long])

class CleanerController(environment: BigGraphEnvironment) {
  private val methods = List(
    Method(
      "notMetaGraphContents",
      "Files which do not exist in the meta-graph",
      """Truly orphan files. These are created e.g. when the kite meta directory
      is deleted. Deleting these should not have any side effects.""",
      metaGraphContents),
    Method(
      "notReferredFromProjectTransitively",
      "Files not associated with any project",
      """Everything except the transitive dependencies of the existing projects.""",
      transitivelyReferredFromProject),
    Method(
      "notReferredFromProject",
      "Files not associated with the current state of any project",
      """Everything except the immediate dependencies of the existing projects.
      Deleting these may cause recalculations or errors when using undo or editing
      the project history. It can cause re-imports which may lead to unexpected
      data changes or errors.""",
      referredFromProject))

  def getDataFilesStatus(user: serving.User, req: serving.Empty): DataFilesStatus = {
    assert(user.isAdmin, "Only administrator users can use the cleaner.")
    val files = getAllFiles()
    val allFiles = files.entities ++ files.operations ++ files.scalars

    DataFilesStatus(
      DataFilesStats(
        fileCount = allFiles.size,
        totalSize = allFiles.map(_._2).sum),
      methods.map { m =>
        getDataFilesStats(m.id, m.name, m.desc, m.filesToKeep(), files)
      })
  }

  private def getAllFiles(): AllFiles = {
    AllFiles(
      getAllFilesInDir(io.EntitiesDir),
      getAllFilesInDir(io.OperationsDir),
      getAllFilesInDir(io.ScalarsDir))
  }

  // Return all files and dirs and their respective sizes in bytes in a
  // certain directory. Directories marked as deleted are not included.
  private def getAllFilesInDir(dir: String): Map[String, Long] = {
    val hadoopFileDir = environment.dataManager.repositoryPath / dir
    hadoopFileDir.listStatus.filterNot {
      subDir => subDir.getPath().toString contains io.DeletedSfx
    }.map { subDir =>
      val baseName = subDir.getPath().getName()
      baseName -> (hadoopFileDir / baseName).getContentSummary.getSpaceConsumed
    }.toMap
  }

  private def metaGraphContents(): Set[String] = {
    allFilesFromSourceOperation(environment.metaGraphManager.getOperationInstances())
  }

  private def referredFromProject(): Set[String] = {
    implicit val manager = environment.metaGraphManager
    val operations = operationsFromAllProjects()
    allFilesFromSourceOperation(operations)
  }

  private def transitivelyReferredFromProject(): Set[String] = {
    implicit val manager = environment.metaGraphManager
    var dependentOperations = operationsFromAllProjects()
    var operations = dependentOperations
    // Collect the dependencies of the dependencies until there is none.
    do {
      // Expanding the dependencies of 'dependentOperations', minus 'operations' to avoid
      // expanding the same operation twice.
      dependentOperations = (dependentOperations.map {
        case (_, o) => o.inputs.all.map {
          case (_, i) => (i.source.gUID -> i.source)
        }
      }.foldLeft(Map[UUID, MetaGraphOperationInstance]())((a, b) => a ++ b)) -- operations.keys
      operations = operations ++ dependentOperations
    } while (dependentOperations.size > 0)
    allFilesFromSourceOperation(operations.toMap)
  }

  private def operationsFromAllProjects()(
    implicit manager: MetaGraphManager): Map[UUID, MetaGraphOperationInstance] = {
    val operations = new HashMap[UUID, MetaGraphOperationInstance]
    for (project <- Operation.projects) {
      operations ++= operationsFromProject(project)
      for (segmentation <- project.segmentations) {
        operations ++= operationsFromProject(segmentation.project)
      }
    }
    operations.toMap
  }

  // Returns the operations mapped by their ID strings which created
  // the vertices, edges, attributes and scalars of this project.
  private def operationsFromProject(
    project: Project): Map[UUID, MetaGraphOperationInstance] = {
    val operations = new HashMap[UUID, MetaGraphOperationInstance]
    if (project.vertexSet != null) {
      operations += operationWithID(project.vertexSet.source)
    }
    if (project.edgeBundle != null) {
      operations += operationWithID(project.edgeBundle.source)
    }
    operations ++= project.scalars.map {
      case (_, s) => operationWithID(s.source)
    }
    operations ++= project.vertexAttributes.map {
      case (_, a) => operationWithID(a.source)
    }
    operations ++= project.edgeAttributes.map {
      case (_, a) => operationWithID(a.source)
    }
    operations.toMap
  }

  private def operationWithID(
    operation: MetaGraphOperationInstance): (UUID, MetaGraphOperationInstance) = {
    (operation.gUID, operation)
  }

  // Returns the set of ID strings of all the entities and scalars created by
  // the operations, plus the ID strings of the operations themselves.
  // Note that these ID strings are the base names of the corresponding
  // data directories.
  private def allFilesFromSourceOperation(
    operations: Map[UUID, MetaGraphOperationInstance]): Set[String] = {
    val files = new HashSet[String]
    for ((id, operation) <- operations) {
      files += id.toString
      files ++= operation.outputs.all.values.map { e => e.gUID.toString }
    }
    files.toSet
  }

  private def getDataFilesStats(
    id: String,
    name: String,
    desc: String,
    filesToKeep: Set[String],
    allFiles: AllFiles): DataFilesStats = {
    val filesToDelete = (allFiles.entities ++ allFiles.operations ++ allFiles.scalars) -- filesToKeep
    DataFilesStats(id, name, desc, filesToDelete.size, filesToDelete.map(_._2).sum)
  }

  def markFilesDeleted(user: serving.User, req: MarkDeletedRequest): Unit = synchronized {
    assert(user.isAdmin, "Only administrators can mark files deleted.")
    assert(methods.map { m => m.id } contains req.method,
      s"Unkown orphan file marking method: ${req.method}")
    log.info(s"${user.email} attempting to mark orphan files deleted using '${req.method}'.")
    val files = getAllFiles()
    val filesToKeep = methods.find(m => m.id == req.method).get.filesToKeep()
    markDeleted(io.EntitiesDir, files.entities.keys.toSet -- filesToKeep)
    markDeleted(io.OperationsDir, files.operations.keys.toSet -- filesToKeep)
    markDeleted(io.ScalarsDir, files.scalars.keys.toSet -- filesToKeep)
  }

  private def markDeleted(dir: String, files: Set[String]): Unit = {
    val hadoopFileDir = environment.dataManager.repositoryPath / dir
    for (file <- files) {
      (hadoopFileDir / file).renameTo(hadoopFileDir / (file + io.DeletedSfx))
    }
    log.info(s"${files.size} files marked deleted in ${hadoopFileDir.path}.")
  }

  def deleteMarkedFiles(user: serving.User, req: serving.Empty): Unit = synchronized {
    assert(user.isAdmin, "Only administrators can delete marked files.")
    log.info(s"${user.email} attempting to delete marked files.")
    deleteMarkedFilesInDir(io.EntitiesDir)
    deleteMarkedFilesInDir(io.OperationsDir)
    deleteMarkedFilesInDir(io.ScalarsDir)
  }

  private def deleteMarkedFilesInDir(dir: String): Unit = {
    val hadoopFileDir = environment.dataManager.repositoryPath / dir
    hadoopFileDir.listStatus.filter {
      subDir => subDir.getPath().toString contains io.DeletedSfx
    }.map { subDir =>
      (hadoopFileDir / subDir.getPath().getName()).delete()
    }
  }
}
