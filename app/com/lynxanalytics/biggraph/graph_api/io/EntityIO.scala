// Classes for reading and writing EntityData to storage.

package com.lynxanalytics.biggraph.graph_api.io

import com.lynxanalytics.biggraph.graph_api.TypeTagToFormat
import org.apache.hadoop
import org.apache.spark
import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.RDD
import play.api.libs.json
import scala.reflect.runtime.universe._

import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util._
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_util._

object IOContext {
  object TaskType {
    val MAP = TaskType(isMap = true)
    val REDUCE = TaskType(isMap = false)
  }
  case class TaskType(isMap: Boolean)

  // Encompasses the Hadoop OutputFormat, Writer, and Committer in one object.
  private class TaskFile(
      tracker: String, stage: Int, taskType: TaskType,
      task: Int, attempt: Int, file: HadoopFile,
      collection: TaskFileCollection) {
    import hadoop.mapreduce.lib.output.SequenceFileOutputFormat
    val fmt = new SequenceFileOutputFormat[hadoop.io.LongWritable, hadoop.io.BytesWritable]()
    val context = {
      val config = new hadoop.mapred.JobConf(file.hadoopConfiguration)
      // Set path for Hadoop 2.
      config.set(
        hadoop.mapreduce.lib.output.FileOutputFormat.OUTDIR,
        file.resolvedNameWithNoCredentials)
      config.setOutputKeyClass(classOf[hadoop.io.LongWritable])
      config.setOutputValueClass(classOf[hadoop.io.BytesWritable])
      spark.EntityIOHelper.createTaskAttemptContext(
        config, tracker, stage, taskType.isMap, task, attempt)
    }
    val committer = fmt.getOutputCommitter(context)
    lazy val writer = {
      val w = fmt.getRecordWriter(context)
      collection.registerForClosing(this)
      w
    }
  }

  private class TaskFileCollection(
      tracker: String, stage: Int, taskType: TaskType, task: Int, attempt: Int) {
    val toClose = new collection.mutable.ListBuffer[TaskFile]()
    def createTaskFile(path: HadoopFile) = {
      new TaskFile(tracker, stage, taskType, task, attempt, path, this)
    }
    def registerForClosing(file: TaskFile) = {
      toClose += file
    }
    // Closes any writers that were created.
    def closeWriters() = for (file <- toClose) file.writer.close(file.context)
  }
}

case class IOContext(dataRoot: DataRoot, sparkContext: spark.SparkContext) {
  def partitionedPath(entity: MetaGraphEntity): HadoopFileLike =
    dataRoot / io.PartitionedDir / entity.gUID.toString

  def partitionedPath(entity: MetaGraphEntity, numPartitions: Int): HadoopFileLike =
    partitionedPath(entity) / numPartitions.toString

  // Writes multiple attributes and their vertex set to disk. The attributes are given in a
  // single RDD which will be iterated over only once.
  // It's the callers responsibility to make sure that the Seqs in data have elements of the right
  // type, corresponding to the given attributes. For wrong types, the behavior is unspecified,
  // it may or may not fail at write time.
  // Don't let the AnyType type parameter fool you, it has really no signifance, you can basically
  // pass in an RDD of any kind of Seq you like. It's only needed because stupid RDDs are not
  // covariant, so taking AttributeRDD[Seq[_]] wouldn't be generic enough.
  def writeAttributes[AnyType](
    attributes: Seq[Attribute[_]],
    data: AttributeRDD[Seq[AnyType]]) = {

    val vs = attributes.head.vertexSet
    for (attr <- attributes) assert(attr.vertexSet == vs, s"$attr is not for $vs")

    // Delete output directories.
    val doesNotExist = new VertexSetIO(vs, this).delete()
    assert(doesNotExist, s"Cannot delete directory of $vs")
    for (attr <- attributes) {
      val doesNotExist = new AttributeIO(attr, this).delete()
      assert(doesNotExist, s"Cannot delete directory of $attr")
    }

    val outputEntities: Seq[MetaGraphEntity] = attributes :+ vs
    val paths = outputEntities.map(e => partitionedPath(e, data.partitions.size).forWriting)

    val trackerID = Timestamp.toString
    val rddID = data.id
    val count = sparkContext.accumulator[Long](0L, "Line count")
    val unitSerializer = EntitySerializer.forType[Unit]
    val serializers = attributes.map(EntitySerializer.forAttribute(_))
    // writeShard is the function that runs on the executors. It writes out one partition of the
    // RDD into one part-xxxx file per column, plus one for the vertex set.
    val writeShard = (task: spark.TaskContext, iterator: Iterator[(ID, Seq[Any])]) => {
      val collection = new IOContext.TaskFileCollection(
        trackerID, rddID, IOContext.TaskType.REDUCE, task.partitionId, task.attemptNumber)
      val files = paths.map(collection.createTaskFile(_))
      try {
        val verticesWriter = files.last.writer
        for (file <- files) file.committer.setupTask(file.context)
        val filesAndSerializers = files zip serializers
        for ((id, cols) <- iterator) {
          count += 1
          val key = new hadoop.io.LongWritable(id)
          for (((file, serializer), col) <- (filesAndSerializers zip cols) if col != null) {
            val value = serializer.unsafeSerialize(col)
            file.writer.write(key, value)
          }
          verticesWriter.write(key, unitSerializer.serialize(()))
        }
      } finally collection.closeWriters()
      for (file <- files) file.committer.commitTask(file.context)
    }
    // The jobs are committed under the guise of MAP tasks, so they don't collide with the
    // REDUCE tasks used to commit tasks. (#2804)
    val collection =
      new IOContext.TaskFileCollection(trackerID, rddID, IOContext.TaskType.MAP, 0, 0)
    val files = paths.map(collection.createTaskFile(_))
    for (file <- files) file.committer.setupJob(file.context)
    sparkContext.runJob(data, writeShard)
    for (file <- files) file.committer.commitJob(file.context)
    // Write metadata files.
    val vertexSetMeta = EntityMetadata(count.value, Some(unitSerializer.name))
    vertexSetMeta.write(partitionedPath(vs).forWriting)
    for ((attr, serializer) <- attributes zip serializers)
      EntityMetadata(count.value, Some(serializer.name)).write(partitionedPath(attr).forWriting)
  }
}

object EntityIO {
  // These "constants" are mutable for the sake of testing.
  var verticesPerPartition =
    util.Properties.envOrElse("KITE_VERTICES_PER_PARTITION", "200000").toInt
  var tolerance =
    util.Properties.envOrElse("KITE_VERTICES_PARTITION_TOLERANCE", "2.0").toDouble

  implicit val fEntityMetadata = json.Json.format[EntityMetadata]
  def operationPath(dataRoot: DataRoot, instance: MetaGraphOperationInstance) =
    dataRoot / io.OperationsDir / instance.gUID.toString
}

case class EntityMetadata(lines: Long, serialization: Option[String]) {
  def write(path: HadoopFile) = {
    import EntityIO.fEntityMetadata
    val metaFile = path / io.Metadata
    assert(!metaFile.exists, s"Metafile $metaFile should not exist before we write it.")
    val metaFileCreated = path / io.MetadataCreate
    metaFileCreated.deleteIfExists()
    val j = json.Json.toJson(this)
    metaFileCreated.createFromStrings(json.Json.prettyPrint(j))
    metaFileCreated.renameTo(metaFile)
  }
}

abstract class EntityIO(val entity: MetaGraphEntity, context: IOContext) {
  def correspondingVertexSet: Option[VertexSet] = None
  def read(parent: Option[VertexSetData] = None): EntityData
  def write(data: EntityData): Unit
  def delete(): Boolean
  def exists: Boolean
  def mayHaveExisted: Boolean // May be outdated or incorrectly true.

  protected val dataRoot = context.dataRoot
  protected val sc = context.sparkContext
  protected def operationMayHaveExisted = EntityIO.operationPath(dataRoot, entity.source).mayHaveExisted
  protected def operationExists = (EntityIO.operationPath(dataRoot, entity.source) / io.Success).exists
}

class ScalarIO[T](entity: Scalar[T], context: IOContext)
    extends EntityIO(entity, context) {

  def read(parent: Option[VertexSetData]): ScalarData[T] = {
    assert(parent == None, s"Scalar read called with parent $parent")
    log.info(s"PERF Loading scalar $entity from disk")
    val jsonString = serializedScalarFileName.forReading.readAsString()
    val format = TypeTagToFormat.typeTagToFormat(entity.typeTag)
    val value = format.reads(json.Json.parse(jsonString)).get
    log.info(s"PERF Loaded scalar $entity from disk")
    new ScalarData[T](entity, value)
  }

  def write(data: EntityData): Unit = {
    val scalarData = data.asInstanceOf[ScalarData[T]]
    log.info(s"PERF Writing scalar $entity to disk")
    val format = TypeTagToFormat.typeTagToFormat(entity.typeTag)
    val output = format.writes(scalarData.value)
    val targetDir = path.forWriting
    targetDir.mkdirs
    serializedScalarFileName.forWriting.createFromStrings(json.Json.prettyPrint(output))
    successPath.forWriting.createFromStrings("")
    log.info(s"PERF Written scalar $entity to disk")
  }
  def delete() = path.forWriting.deleteIfExists()
  def exists = operationExists && (path / Success).exists
  def mayHaveExisted = operationMayHaveExisted && path.mayHaveExisted

  private def path = dataRoot / ScalarsDir / entity.gUID.toString
  private def serializedScalarFileName: HadoopFileLike = path / "serialized_data"
  private def successPath: HadoopFileLike = path / Success
}

case class RatioSorter(elements: Seq[Int], desired: Int) {
  assert(desired > 0, "RatioSorter only supports positive integers")
  assert(elements.filter(_ <= 0).isEmpty, "RatioSorter only supports positive integers")
  private val sorted: Seq[(Int, Double)] = {
    elements.map { a =>
      val aa = a.toDouble
      if (aa > desired) (a, aa / desired)
      else (a, desired.toDouble / aa)
    }
      .sortBy(_._2)
  }

  val best: Option[Int] = sorted.map(_._1).headOption

  def getBestWithinTolerance(tolerance: Double): Option[Int] = {
    sorted.filter(_._2 < tolerance).map(_._1).headOption
  }

}

abstract class PartitionedDataIO[T, DT <: EntityRDDData[T]](entity: MetaGraphEntity,
                                                            context: IOContext)
    extends EntityIO(entity, context) {

  // This class reflects the current state of the disk during the read operation
  case class EntityLocationSnapshot(availablePartitions: Map[Int, HadoopFile]) {
    val hasPartitionedDirs = availablePartitions.nonEmpty
    val metaPathExists = metaFile.forReading.exists
    val hasPartitionedData = hasPartitionedDirs && metaPathExists

    val legacyPathExists = (legacyPath / io.Success).forReading.exists
    assert(hasPartitionedData || legacyPathExists,
      s"Legacy path ${legacyPath.forReading} does not exist," +
        s" and there seems to be no valid data in ${partitionedPath.forReading}")

    private lazy val metadata: EntityMetadata = {
      import EntityIO.fEntityMetadata
      val j = json.Json.parse(metaFile.forReading.readAsString)
      j.as[EntityMetadata]
    }

    val numVertices =
      if (hasPartitionedData) metadata.lines
      else legacyRDD.count

    def serialization = {
      assert(hasPartitionedData, s"EntitySerialization cannot be used with legacy data. ($entity)")
      metadata.serialization.getOrElse("kryo")
    }
  }

  def read(parent: Option[VertexSetData] = None): DT = {
    val entityLocation = EntityLocationSnapshot(computeAvailablePartitions)
    val pn = parent.map(_.rdd.partitions.size).getOrElse(selectPartitionNumber(entityLocation))
    val partitioner = parent.map(_.rdd.partitioner.get).getOrElse(new HashPartitioner(pn))

    val (file, serialization) =
      if (entityLocation.availablePartitions.contains(pn))
        (entityLocation.availablePartitions(pn), entityLocation.serialization)
      else
        repartitionTo(entityLocation, partitioner)

    val dataRead = finalRead(
      file, entityLocation.numVertices, serialization, partitioner, parent)
    assert(dataRead.rdd.partitions.size == pn,
      s"finalRead mismatch: ${dataRead.rdd.partitions.size} != $pn")
    dataRead
  }

  def write(data: EntityData): Unit = {
    assert(data.entity == entity, s"Tried to write $data through EntityIO for $entity.")
    val rddData: EntityRDDData[T] = castData(data)
    log.info(s"PERF Instantiating entity $entity on disk")
    val rdd = rddData.rdd
    val partitions = rdd.partitions.size
    val (lines, serialization) = targetDir(partitions).saveEntityRDD(rdd, valueTypeTag)
    val metadata = EntityMetadata(lines, Some(serialization))
    metadata.write(partitionedPath.forWriting)
    log.info(s"PERF Instantiated entity $entity on disk")
  }

  // The subclasses know the specific type and can thus make a safer cast.
  def castData(data: EntityData): EntityRDDData[T]

  def valueTypeTag: TypeTag[T] // The TypeTag of the values we write out.

  def delete(): Boolean = {
    legacyPath.forWriting.deleteIfExists() && partitionedPath.forWriting.deleteIfExists()
  }

  def exists = operationExists && (existsPartitioned || existsAtLegacy)

  def mayHaveExisted = operationMayHaveExisted && (partitionedPath.mayHaveExisted || legacyPath.mayHaveExisted)

  private val partitionedPath = context.partitionedPath(entity)
  private val metaFile = partitionedPath / io.Metadata

  private def targetDir(numPartitions: Int) =
    context.partitionedPath(entity, numPartitions).forWriting

  private def computeAvailablePartitions = {
    val subDirs = (partitionedPath / "*").list
    val number = "[1-9][0-9]*".r
    val numericSubdirs = subDirs.filter(x => number.pattern.matcher(x.path.getName).matches)
    val existingCandidates = numericSubdirs.filter(x => (x / Success).exists)
    val resultList = existingCandidates.map { x => (x.path.getName.toInt, x) }
    resultList.toMap
  }

  // This method performs the actual reading of the rdddata, from a path
  // The parent VertexSetData is given for EdgeBundleData and AttributeData[T] so that
  // the corresponding data will be co-located.
  // A partitioner is also passed, because we don't want to create another one for VertexSetData
  protected def finalRead(path: HadoopFile,
                          count: Long,
                          serialization: String,
                          partitioner: spark.Partitioner,
                          parent: Option[VertexSetData] = None): DT

  protected def legacyLoadRDD(path: HadoopFile): SortedRDD[Long, T]

  private def bestPartitionedSource(
    entityLocation: EntityLocationSnapshot,
    desiredPartitionNumber: Int) = {
    assert(entityLocation.availablePartitions.nonEmpty,
      s"There should be valid sub directories in $partitionedPath")
    val ratioSorter =
      RatioSorter(entityLocation.availablePartitions.map(_._1).toSeq, desiredPartitionNumber)
    entityLocation.availablePartitions(ratioSorter.best.get)
  }

  // Returns the file and the serialization format.
  private def repartitionTo(entityLocation: EntityLocationSnapshot,
                            partitioner: spark.Partitioner): (HadoopFile, String) = {
    if (entityLocation.hasPartitionedData)
      repartitionFromPartitionedRDD(entityLocation, partitioner)
    else
      repartitionFromLegacyRDD(entityLocation, partitioner)
  }

  // Returns the file and the serialization format.
  private def repartitionFromPartitionedRDD(
    entityLocation: EntityLocationSnapshot,
    partitioner: spark.Partitioner): (HadoopFile, String) = {
    val pn = partitioner.numPartitions
    val from = bestPartitionedSource(entityLocation, pn)
    val oldRDD = from.loadEntityRawRDD(sc)
    val newRDD = oldRDD.sort(partitioner)
    val newFile = targetDir(pn)
    val lines = newFile.saveEntityRawRDD(newRDD)
    assert(entityLocation.numVertices == lines,
      s"Unexpected row count (${entityLocation.numVertices} != $lines) for $entity")
    (newFile, entityLocation.serialization)
  }

  // Returns the file and the serialization format.
  private def repartitionFromLegacyRDD(
    entityLocation: EntityLocationSnapshot,
    partitioner: spark.Partitioner): (HadoopFile, String) = {
    assert(entityLocation.legacyPathExists,
      s"There should be a valid legacy path at $legacyPath")
    val pn = partitioner.numPartitions
    val oldRDD = legacyRDD
    implicit val ct = RuntimeSafeCastable.classTagFromTypeTag(valueTypeTag)
    val newRDD = oldRDD.sort(partitioner)
    val newFile = targetDir(pn)
    val (lines, serialization) = newFile.saveEntityRDD[T](newRDD, valueTypeTag)
    assert(entityLocation.numVertices == lines,
      s"Unexpected row count (${entityLocation.numVertices} != $lines) for $entity")
    val metadata = EntityMetadata(lines, Some(serialization))
    metadata.write(partitionedPath.forWriting)
    (newFile, serialization)
  }

  private def legacyRDD = legacyLoadRDD(legacyPath.forReading)

  private def desiredPartitions(entityLocation: EntityLocationSnapshot) = {
    val vertices = entityLocation.numVertices
    val p = Math.ceil(vertices.toDouble / EntityIO.verticesPerPartition).toInt
    // Always have at least 1 partition.
    p max 1
  }

  private def selectPartitionNumber(entityLocation: EntityLocationSnapshot): Int = {
    val desired = desiredPartitions(entityLocation)
    val ratioSorter = RatioSorter(entityLocation.availablePartitions.map(_._1).toSeq, desired)
    ratioSorter.getBestWithinTolerance(EntityIO.tolerance).getOrElse(desired)
  }

  private def legacyPath = dataRoot / EntitiesDir / entity.gUID.toString
  private def existsAtLegacy = (legacyPath / Success).exists
  private def existsPartitioned = computeAvailablePartitions.nonEmpty && metaFile.exists

  protected def enforceCoLocationWithParent[T](rawRDD: RDD[(Long, T)],
                                               parent: VertexSetData): RDD[(Long, T)] = {
    val vsRDD = parent.rdd.copyWithAncestorsCached
    // Enforcing colocation:
    assert(vsRDD.partitions.size == rawRDD.partitions.size,
      s"$vsRDD and $rawRDD should have the same number of partitions, " +
        s"but ${vsRDD.partitions.size} != ${rawRDD.partitions.size}")
    vsRDD.zipPartitions(rawRDD, preservesPartitioning = true) {
      (it1, it2) => it2
    }
  }
}

class VertexSetIO(entity: VertexSet, context: IOContext)
    extends PartitionedDataIO[Unit, VertexSetData](entity, context) {

  def legacyLoadRDD(path: HadoopFile): SortedRDD[Long, Unit] = {
    path.loadLegacyEntityRDD[Unit](sc)
  }

  def finalRead(path: HadoopFile,
                count: Long,
                serialization: String,
                partitioner: spark.Partitioner,
                parent: Option[VertexSetData]): VertexSetData = {
    assert(parent == None, s"finalRead for $entity should not take a parent option")
    val rdd = path.loadEntityRDD[Unit](sc, serialization)
    new VertexSetData(entity, rdd.asUniqueSortedRDD(partitioner), Some(count))
  }

  def castData(data: EntityData) = data.asInstanceOf[VertexSetData]

  def valueTypeTag = typeTag[Unit]
}

class EdgeBundleIO(entity: EdgeBundle, context: IOContext)
    extends PartitionedDataIO[Edge, EdgeBundleData](entity, context) {

  override def correspondingVertexSet = Some(entity.idSet)

  def legacyLoadRDD(path: HadoopFile): SortedRDD[Long, Edge] = {
    path.loadLegacyEntityRDD[Edge](sc)
  }

  def finalRead(path: HadoopFile,
                count: Long,
                serialization: String,
                partitioner: spark.Partitioner,
                parent: Option[VertexSetData]): EdgeBundleData = {
    assert(partitioner eq parent.get.rdd.partitioner.get,
      s"Partitioner mismatch for $entity.")
    val rdd = path.loadEntityRDD[Edge](sc, serialization)
    val coLocated = enforceCoLocationWithParent(rdd, parent.get)
    new EdgeBundleData(
      entity,
      coLocated.asUniqueSortedRDD(partitioner),
      Some(count))
  }

  def castData(data: EntityData) = data.asInstanceOf[EdgeBundleData]

  def valueTypeTag = typeTag[Edge]
}

class AttributeIO[T](entity: Attribute[T], context: IOContext)
    extends PartitionedDataIO[T, AttributeData[T]](entity, context) {
  override def correspondingVertexSet = Some(entity.vertexSet)

  def legacyLoadRDD(path: HadoopFile): SortedRDD[Long, T] = {
    implicit val ct = entity.classTag
    path.loadLegacyEntityRDD[T](sc)
  }

  def finalRead(path: HadoopFile,
                count: Long,
                serialization: String,
                partitioner: spark.Partitioner,
                parent: Option[VertexSetData]): AttributeData[T] = {
    assert(partitioner eq parent.get.rdd.partitioner.get,
      s"Partitioner mismatch for $entity.")
    implicit val ct = entity.classTag
    implicit val tt = entity.typeTag
    val rdd = path.loadEntityRDD[T](sc, serialization)
    val coLocated = enforceCoLocationWithParent(rdd, parent.get)
    new AttributeData[T](
      entity,
      coLocated.asUniqueSortedRDD(partitioner),
      Some(count))
  }

  def castData(data: EntityData) =
    data.asInstanceOf[AttributeData[_]].runtimeSafeCast(valueTypeTag)

  def valueTypeTag = entity.typeTag
}
