package com.lynxanalytics.biggraph.graph_api

import _root_.io.grpc.netty.NettyChannelBuilder
import _root_.io.grpc.netty.GrpcSslContexts
import _root_.io.grpc.StatusRuntimeException
import _root_.io.grpc.ManagedChannelBuilder
import _root_.io.grpc.stub.StreamObserver
import com.lynxanalytics.biggraph.graph_api.proto._
import com.lynxanalytics.biggraph.graph_util.LoggedEnvironment
import java.io.File
import scala.reflect.runtime.universe._
import scala.concurrent.{ Promise, Future }
import scala.util.{ Success, Failure }
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext

class SingleResponseStreamObserver[T] extends StreamObserver[T] {
  private val promise = Promise[T]()
  val future = SafeFuture.wrap(promise.future)
  var responseArrived = false
  def onNext(r: T) {
    assert(!responseArrived, s"Two responses arrived, while we expected only one.")
    responseArrived = true
    promise.complete(Success(r))
  }
  def onError(t: Throwable) {
    promise.complete(Failure(t))
  }
  def onCompleted() {
    if (!responseArrived) {
      val e = new Exception("No response arrived.")
      promise.complete(Failure(e))
    }
  }
}

class SphynxClient(host: String, port: Int, certDir: String)(implicit ec: ExecutionContext) {
  // Exchanges messages with Sphynx.

  private val channel = NettyChannelBuilder.forAddress(host, port)
    .sslContext(GrpcSslContexts.forClient().trustManager(new File(s"$certDir/cert.pem")).build())
    .build();

  private val blockingStub = SphynxGrpc.newBlockingStub(channel)
  private val asyncStub = SphynxGrpc.newStub(channel)

  def canCompute(operationMetadataJSON: String): Boolean = {
    val request = SphynxOuterClass.CanComputeRequest.newBuilder().setOperation(operationMetadataJSON).build()
    val response = blockingStub.canCompute(request)
    response.getCanCompute
  }

  def compute(operationMetadataJSON: String): SafeFuture[Unit] = {
    val request = SphynxOuterClass.ComputeRequest.newBuilder().setOperation(operationMetadataJSON).build()
    val obs = new SingleResponseStreamObserver[SphynxOuterClass.ComputeReply]
    asyncStub.compute(request, obs)
    obs.future.map(_ => ())
  }

  def getScalar[T](scalar: Scalar[T]): SafeFuture[T] = {
    val gUIDString = scalar.gUID.toString()
    val request = SphynxOuterClass.GetScalarRequest.newBuilder().setGuid(gUIDString).build()
    val format = TypeTagToFormat.typeTagToFormat(scalar.typeTag)
    val obs = new SingleResponseStreamObserver[SphynxOuterClass.GetScalarReply]
    asyncStub.getScalar(request, obs)
    obs.future.map(r => format.reads(Json.parse(r.getScalar)).get)
  }

  def writeToUnorderedDisk(e: MetaGraphEntity): SafeFuture[Unit] = {
    // In SphynxMemory, vertices are indexed from 0 to n. This method asks Sphynx
    // to reindex vertices to use Spark-side indices and write the result into
    // a file on UnorderedSphynxDisk. For this, some entity types need extra vertex set guids

    val request = e match {
      case a: Attribute[_] =>
        SphynxOuterClass.WriteToUnorderedDiskRequest.newBuilder()
          .setGuid(e.gUID.toString)
          .setVsguid1(a.vertexSet.gUID.toString).build()
      case eb: EdgeBundle =>
        SphynxOuterClass.WriteToUnorderedDiskRequest.newBuilder()
          .setGuid(e.gUID.toString)
          .setVsguid1(eb.srcVertexSet.gUID.toString)
          .setVsguid2(eb.dstVertexSet.gUID.toString).build()
      case _ =>
        SphynxOuterClass.WriteToUnorderedDiskRequest.newBuilder()
          .setGuid(e.gUID.toString).build()
    }
    val obs = new SingleResponseStreamObserver[SphynxOuterClass.WriteToUnorderedDiskReply]
    asyncStub.writeToUnorderedDisk(request, obs)
    obs.future.map(_ => ())
  }

  def hasOnOrderedSphynxDisk(e: MetaGraphEntity): Boolean = {
    val request = SphynxOuterClass.HasOnOrderedSphynxDiskRequest.newBuilder().setGuid(e.gUID.toString).build()
    val response = blockingStub.hasOnOrderedSphynxDisk(request)
    response.getHasOnDisk
  }

  def hasInSphynxMemory(e: MetaGraphEntity): Boolean = {
    val request = SphynxOuterClass.HasInSphynxMemoryRequest.newBuilder().setGuid(e.gUID.toString).build()
    val response = blockingStub.hasInSphynxMemory(request)
    response.getHasInMemory
  }

  def readFromOrderedSphynxDisk(e: MetaGraphEntity): SafeFuture[Unit] = {
    val guid = e.gUID.toString()
    val request = SphynxOuterClass.ReadFromOrderedSphynxDiskRequest.newBuilder().setGuid(guid).build()
    val obs = new SingleResponseStreamObserver[SphynxOuterClass.ReadFromOrderedSphynxDiskReply]
    asyncStub.readFromOrderedSphynxDisk(request, obs)
    obs.future.map(_ => ())
  }

}
