// Convenient Hadoop file interface.
package com.lynxanalytics.biggraph.graph_util

import com.esotericsoftware.kryo
import org.apache.hadoop
import org.apache.spark
import org.apache.spark.SparkContext.rddToPairRDDFunctions
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException

import com.lynxanalytics.biggraph.bigGraphLogger
import com.lynxanalytics.biggraph.spark_util.BigGraphSparkContext
import com.lynxanalytics.biggraph.spark_util.RDDUtils

object HadoopFile {

  def apply(str: String) = {
    val v = RootRepository.splitSymbolicPattern(str)
    new HadoopFile(v._1, v._2)
  }
}

case class HadoopFile(rootSymbol: String, relativePath: String) {
  private val s3nWithCreadentialsPattern = "(s3n?)://(.+):(.+)@(.+)".r
  private val s3nNoCredentialsPattern = "(s3n?)://(.+)".r

  val symbolicName = rootSymbol + relativePath
  val resolvedName = RootRepository.getRootInfo(rootSymbol) + relativePath

  val (resolvedNameWithNoCredentials, awsID, awsSecret) = resolvedName match {
    case s3nWithCreadentialsPattern(scheme, key, secret, relPath) =>
      (scheme + "://" + relPath, key, secret)
    case _ =>
      (resolvedName, "", "")
  }

  private def hasCredentials = awsID.nonEmpty

  override def toString = symbolicName

  def hadoopConfiguration(): hadoop.conf.Configuration = {
    val conf = new hadoop.conf.Configuration()
    if (hasCredentials) {
      conf.set("fs.s3n.awsAccessKeyId", awsID)
      conf.set("fs.s3n.awsSecretAccessKey", awsSecret)
    }
    return conf
  }

  private def reinstateCredentialsIfNeeded(hadoopOutput: String): String = {
    if (hasCredentials) {
      hadoopOutput match {
        case s3nNoCredentialsPattern(scheme, path) =>
          scheme + "://" + awsID + ":" + awsSecret + "@" + path
      }
    } else {
      hadoopOutput
    }
  }

  private def computeRelativePathFromHadoopOutput(hadoopOutput: String): String = {
    val hadoopOutputWithCredentials = reinstateCredentialsIfNeeded(hadoopOutput)
    val resolution = RootRepository.getRootInfo(rootSymbol)
    assert(hadoopOutputWithCredentials.startsWith(resolution),
      "Bad prefix match: $hadoopOutputWithCredentials should start with $resolution")
    hadoopOutputWithCredentials.drop(resolution.length)
  }

  def copyUpdateRelativePath(hadoopOutput: String): HadoopFile = {
    this.copy(relativePath = computeRelativePathFromHadoopOutput(hadoopOutput))
  }

  @transient lazy val fs = hadoop.fs.FileSystem.get(uri, hadoopConfiguration)
  @transient lazy val uri = path.toUri
  @transient lazy val path = new hadoop.fs.Path(resolvedNameWithNoCredentials)
  def open() = fs.open(path)
  def create() = fs.create(path)
  def exists() = fs.exists(path)
  def reader() = new BufferedReader(new InputStreamReader(open))
  def readAsString() = {
    val r = reader()
    Stream.continually(r.readLine()).takeWhile(_ != null).mkString
  }
  def delete() = fs.delete(path, true)
  def renameTo(fn: HadoopFile) = fs.rename(path, fn.path)
  // globStatus() returns null instead of an empty array when there are no matches.
  private def globStatus = Option(fs.globStatus(path)).getOrElse(Array())
  def list = globStatus.map(st => copyUpdateRelativePath(st.getPath.toString))

  def length = fs.getFileStatus(path).getLen
  def globLength = globStatus.map(_.getLen).sum

  def loadTextFile(sc: spark.SparkContext): spark.rdd.RDD[String] = {
    val conf = hadoopConfiguration
    // Make sure we get many small splits.
    conf.setLong("mapred.max.split.size", 50000000)
    sc.newAPIHadoopFile(
      resolvedNameWithNoCredentials,
      kClass = classOf[hadoop.io.LongWritable],
      vClass = classOf[hadoop.io.Text],
      fClass = classOf[hadoop.mapreduce.lib.input.TextInputFormat],
      conf = conf)
      .map(pair => pair._2.toString)
  }

  def saveAsTextFile(lines: spark.rdd.RDD[String]): Unit = {
    // RDD.saveAsTextFile does not take a hadoop.conf.Configuration argument. So we struggle a bit.
    val hadoopLines = lines.map(x => (hadoop.io.NullWritable.get(), new hadoop.io.Text(x)))
    hadoopLines.saveAsHadoopFile(
      resolvedNameWithNoCredentials,
      keyClass = classOf[hadoop.io.NullWritable],
      valueClass = classOf[hadoop.io.Text],
      outputFormatClass = classOf[hadoop.mapred.TextOutputFormat[hadoop.io.NullWritable, hadoop.io.Text]],
      conf = new hadoop.mapred.JobConf(hadoopConfiguration))
  }

  def createFromStrings(contents: String): Unit = {
    val stream = create()
    try {
      stream.write(contents.getBytes("UTF-8"))
    } finally {
      stream.close()
    }
  }

  def createFromObjectKryo(obj: Any): Unit = {
    /* It is not clear why, but createFromObjectKryo seems to gravely spoil the kryo instance
     * for any future deserialization operation. Basically trying to reuse a kryo instance for
     * deserialization will be orders of magnitude slower than a normal one.
     * We need to understand/fix this decently, but for now this is a stop-gap for the demo.
     * Looks like this might be fixable with a kryo upgrade.
     */
    val myKryo = BigGraphSparkContext.createKryo()
    val output = new kryo.io.Output(create())
    try {
      myKryo.writeClassAndObject(output, obj)
    } finally {
      output.close()
    }
  }

  def loadObjectKryo: Any = {
    val input = new kryo.io.Input(open())
    val res = try {
      RDDUtils.threadLocalKryo.get.readClassAndObject(input)
    } finally {
      input.close()
    }
    res
  }

  def mkdirs(): Unit = {
    fs.mkdirs(path)
  }

  def loadObjectFile[T: scala.reflect.ClassTag](sc: spark.SparkContext): spark.rdd.RDD[T] = {
    import hadoop.mapreduce.lib.input.SequenceFileInputFormat

    sc.newAPIHadoopFile(
      resolvedNameWithNoCredentials,
      kClass = classOf[hadoop.io.NullWritable],
      vClass = classOf[hadoop.io.BytesWritable],
      fClass = classOf[SequenceFileInputFormat[hadoop.io.NullWritable, hadoop.io.BytesWritable]],
      conf = hadoopConfiguration)
      .map(pair => RDDUtils.kryoDeserialize[T](pair._2.getBytes))
  }

  def saveAsObjectFile(data: spark.rdd.RDD[_]): Unit = {
    import hadoop.mapreduce.lib.output.SequenceFileOutputFormat

    val hadoopData = data.map(x =>
      (hadoop.io.NullWritable.get(), new hadoop.io.BytesWritable(RDDUtils.kryoSerialize(x))))
    if (fs.exists(path)) {
      bigGraphLogger.info(s"deleting $path as it already exists (possibly as a result of a failed stage)")
      fs.delete(path, true)
    }
    bigGraphLogger.info(s"saving ${data.name} as object file to ${symbolicName} ${resolvedNameWithNoCredentials}")
    hadoopData.saveAsNewAPIHadoopFile(
      resolvedNameWithNoCredentials,
      keyClass = classOf[hadoop.io.NullWritable],
      valueClass = classOf[hadoop.io.BytesWritable],
      outputFormatClass =
        classOf[SequenceFileOutputFormat[hadoop.io.NullWritable, hadoop.io.BytesWritable]],
      conf = new hadoop.mapred.JobConf(hadoopConfiguration))
  }

  def +(suffix: String): HadoopFile = {
    this.copy(relativePath = relativePath + suffix)
  }

  def /(path_element: String): HadoopFile = {
    this + ("/" + path_element)
  }
}
