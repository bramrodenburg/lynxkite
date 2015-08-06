// Utilities to mark and delete unused data files.

package com.lynxanalytics.biggraph.controllers

import java.util.UUID

import scala.collection.immutable.Set
import scala.collection.immutable.Map
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import scala.collection.mutable.Queue

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
  implicit val manager = environment.metaGraphManager

  private val methods = List(
    Method(
      "notMetaGraphContents",
      "Entities which do not exist in the meta-graph",
      "Truly orphan entities. Cached entities can get orphaned e.g. when the kite meta directory" +
        " is deleted or during a Kite version upgrade. Deleting these should not have any side" +
        " effects.",
      metaGraphContents),
    Method(
      "notReferredFromProjectTransitively",
      "Entities not associated with any project",
      "We consider an entity associated with a project if it's either directly referred to from" +
        " the project or it is used as an input to calculate another assoicated entity",
      transitivelyReferredFromProject),
    Method(
      "notReferredFromProject",
      "Entities not referenced by any project",
      "All entities except those that are vertex sets, edge bundles, attributes or scalars of" +
        " a current project or segmentation",
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
    val operations = operationsFromAllProjects()
    allFilesFromSourceOperation(operations)
  }

  private def transitivelyReferredFromProject(): Set[String] = {
    var operations = operationsFromAllProjects()
    val toExpand = new Queue[UUID] ++ operations.keys
    while (!toExpand.isEmpty) {
      val op = manager.getOperationInstances()(toExpand.dequeue)
      for (input <- op.inputs.all.values) {
        val dependentOp = input.source
        val dependentID = dependentOp.gUID
        if (!(operations isDefinedAt dependentID)) {
          operations += (dependentID -> dependentOp)
          toExpand.enqueue(dependentID)
        }
      }
    }
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
