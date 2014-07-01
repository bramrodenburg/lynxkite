package com.lynxanalytics.biggraph

import java.io.File
import org.apache.spark

import com.lynxanalytics.biggraph.graph_util.Filename

trait SparkContextProvider {
  val sparkContext: spark.SparkContext

  def allowsClusterResize: Boolean = false
  def numInstances: Int = ???
  def setNumInstances(numInstances: Int): Unit = ???
}

class StaticSparkContextProvider(master: String) extends SparkContextProvider {
  val sparkContext = spark_util.BigGraphSparkContext("BigGraphServer", master)
}

trait BigGraphEnvironment extends SparkContextProvider {
  val metaGraphManager: graph_api.MetaGraphManager
  val dataManager: graph_api.DataManager
}

trait StaticDirEnvironment extends BigGraphEnvironment {
  val repositoryDirs: RepositoryDirs

  override lazy val metaGraphManager = new graph_api.MetaGraphManager(repositoryDirs.graphDir)
  override lazy val dataManager = new graph_api.DataManager(
    sparkContext, repositoryDirs.dataDir)
}

trait RepositoryDirs {
  val graphDir: String
  val dataDir: Filename
}
class TemporaryRepositoryDirs extends RepositoryDirs {
  private val sysTempDir = System.getProperty("java.io.tmpdir")
  private val myTempDir = new File(
    "%s/%s-%d".format(sysTempDir, getClass.getName, scala.compat.Platform.currentTime))
  myTempDir.mkdir
  private val graphDirFile = new File(myTempDir, "graph")
  graphDirFile.mkdir
  private val dataDirFile = new File(myTempDir, "data")
  dataDirFile.mkdir

  val graphDir = graphDirFile.toString
  val dataDir = Filename(dataDirFile.toString)
}
