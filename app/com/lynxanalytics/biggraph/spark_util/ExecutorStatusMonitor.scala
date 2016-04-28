// Periodically logs memory and storage usage stats of executors.
package com.lynxanalytics.biggraph.spark_util

import com.lynxanalytics.biggraph.graph_util.LoggedEnvironment
import org.apache.spark
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import org.apache.spark.storage.{ StorageStatus, RDDBlockId }

class ExecutorStatusMonitor(
    sc: spark.SparkContext) extends Thread("executor-status-monitor") {

  private val checkPeriod =
    LoggedEnvironment.envOrElse("KITE_EXECUTOR_STATUS_MONITOR_PERIOD_MILLIS", "60000").toLong

  def rddGetTotal(storageStatus: StorageStatus, memFun: (StorageStatus, Int) => Long): Long = {
    val rddBlocks =
      storageStatus.rddBlocks.keys.toSeq.filter(_.isInstanceOf[RDDBlockId]).map(_.asInstanceOf[RDDBlockId])
    rddBlocks.map(_.rddId).distinct.map {
      id => memFun(storageStatus, id)
    }.sum
  }

  private def logStorageStatus(): Unit = {
    val allStorageStatuses = sc.getExecutorStorageStatus.toSeq
    def rddDisk(storageStatus: StorageStatus, id: Int) = storageStatus.diskUsedByRdd(id)
    def rddMem(storageStatus: StorageStatus, id: Int) = storageStatus.memUsedByRdd(id)
    def rddOffHeap(storageStatus: StorageStatus, id: Int) = storageStatus.offHeapUsedByRdd(id)

    allStorageStatuses.foreach {
      x =>
        val diskUsed = x.diskUsed
        val memUsed = x.memUsed
        val offHeapUsed = x.offHeapUsed

        val executor = x.blockManagerId.host + ":" + x.blockManagerId.port
        val msg =
          s"StorageStatus: executor: $executor" +
            s"  diskUsed: $diskUsed" +
            s"  memUsed: $memUsed" +
            s"  offHeapUsed: $offHeapUsed"
        log.info(msg)
    }
  }

  private def logMemoryStatus(): Unit = {
    val memoryStatus = sc.getExecutorMemoryStatus.toSeq
    memoryStatus.foreach {
      case (e, m) =>
        val msg = s"Memory status: executor: $e  max memory: ${m._1}  remaining memory: ${m._2}"
        log.info(msg)
    }
  }

  override def run(): Unit = {
    while (true) {
      Thread.sleep(checkPeriod)
      logStorageStatus()
      logMemoryStatus()
    }
  }

  setDaemon(true)
  start()
}