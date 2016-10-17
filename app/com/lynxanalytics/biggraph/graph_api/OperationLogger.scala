// OperationLogger will log useful performance data about an operation, including information about
// the inputs and the outputs.

package com.lynxanalytics.biggraph.graph_api

import com.lynxanalytics.biggraph.graph_util.KiteInstanceInfo
import play.api.libs.json
import scala.concurrent.ExecutionContextExecutorService

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }

class OperationLogger(instance: MetaGraphOperationInstance,
                      implicit val ec: ExecutionContextExecutorService) {
  private val marker = "OPERATION_LOGGER_MARKER"
  case class OutputInfo(name: String, gUID: String, partitions: Int, count: Option[Long])
  case class InputInfo(name: String, gUID: String, partitions: Int, count: Option[Long])

  private val outputInfoList = scala.collection.mutable.Queue[SafeFuture[OutputInfo]]()
  private val inputInfoList = scala.collection.mutable.Queue[InputInfo]()
  private var startTime = -1L
  private var stopTime = -1L

  private def elapsedMs(): Long = {
    assert(startTime != -1, "elapsedMs() called before startTimer()")
    assert(stopTime != -1, "elapsedMs() called before stopTimer()")
    stopTime - startTime
  }

  def addOutput(output: SafeFuture[EntityData]): Unit = {
    outputInfoList += output.map {
      o =>
        val rddData = o.asInstanceOf[EntityRDDData[_]]
        OutputInfo(
          rddData.entity.name.name,
          rddData.entity.gUID.toString,
          rddData.rdd.partitions.size,
          rddData.count)
    }
  }

  def startTimer(): Unit = {
    assert(startTime == -1, "startTimer() called more than once")
    startTime = System.currentTimeMillis()
  }

  def stopTimer(): Unit = {
    assert(stopTime == -1, "stopTimer() called more than once")
    stopTime = System.currentTimeMillis()
  }
  def addInput(name: String, input: EntityData): Unit = {
    if (instance.operation.isHeavy) input match {
      case rddData: EntityRDDData[_] =>
        inputInfoList +=
          InputInfo(
            name,
            rddData.entity.gUID.toString,
            rddData.rdd.partitions.size,
            rddData.count)
      case _ => // Ignore scalars
    }
  }

  def logWhenReady(): Unit = {
    val outputsFuture = SafeFuture.sequence(outputInfoList)

    outputsFuture.map {
      outputs => dump(outputs)
    }
  }

  private def dump(outputs: Seq[OutputInfo]): Unit = {

    implicit val formatInput = json.Json.format[InputInfo]
    implicit val formatOutput = json.Json.format[OutputInfo]

    val instanceProperties = json.Json.obj(
      "kiteVersion" -> KiteInstanceInfo.kiteVersion,
      "sparkVersion" -> KiteInstanceInfo.sparkVersion,
      "instanceName" -> KiteInstanceInfo.instanceName
    )

    val out = json.Json.obj(
      "instanceProperties" -> instanceProperties,
      "name" -> instance.operation.toString,
      "timestamp" -> com.lynxanalytics.biggraph.graph_util.Timestamp.toString,
      "guid" -> instance.operation.gUID.toString,
      "elapsedMs" -> elapsedMs(),
      "inputs" -> inputInfoList.sortBy(_.name),
      "outputs" -> outputs.sortBy(_.name)
    )
    log.info(s"$marker $out")
  }
}
