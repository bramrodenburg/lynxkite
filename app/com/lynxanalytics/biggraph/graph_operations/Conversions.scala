package com.lynxanalytics.biggraph.graph_operations

import scala.reflect.runtime.universe._
import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._

object EdgeAttributeToString {
  class Output[T](implicit instance: MetaGraphOperationInstance,
                  inputs: EdgeAttributeInput[T])
      extends MagicOutput(instance) {
    val attr = edgeAttribute[String](inputs.es.entity)
  }
}
case class EdgeAttributeToString[T]()
    extends TypedMetaGraphOp[EdgeAttributeInput[T], EdgeAttributeToString.Output[T]] {
  import EdgeAttributeToString._
  @transient override lazy val inputs = new EdgeAttributeInput[T]
  def outputMeta(instance: MetaGraphOperationInstance) = new Output[T]()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output[T],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val ct = inputs.attr.data.classTag
    output(o.attr, inputs.attr.rdd.mapValues(_.toString))
  }
}

object EdgeAttributeToDouble {
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: EdgeAttributeInput[String])
      extends MagicOutput(instance) {
    val attr = edgeAttribute[Double](inputs.es.entity)
  }
}
case class EdgeAttributeToDouble()
    extends TypedMetaGraphOp[EdgeAttributeInput[String], EdgeAttributeToDouble.Output] {
  import EdgeAttributeToDouble._
  @transient override lazy val inputs = new EdgeAttributeInput[String]
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    output(o.attr, inputs.attr.rdd.flatMapValues(str =>
      if (str.nonEmpty) Some(str.toDouble) else None))
  }
}

object VertexAttributeToString {
  class Output[T](implicit instance: MetaGraphOperationInstance,
                  inputs: VertexAttributeInput[T])
      extends MagicOutput(instance) {
    val attr = vertexAttribute[String](inputs.vs.entity)
  }
  def run[T](attr: VertexAttribute[T])(
    implicit manager: MetaGraphManager): VertexAttribute[String] = {

    import Scripting._
    val op = VertexAttributeToString[T]()
    op(op.attr, attr).result.attr
  }
}
case class VertexAttributeToString[T]()
    extends TypedMetaGraphOp[VertexAttributeInput[T], VertexAttributeToString.Output[T]] {
  import VertexAttributeToString._
  @transient override lazy val inputs = new VertexAttributeInput[T]
  def outputMeta(instance: MetaGraphOperationInstance) = new Output[T]()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output[T],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val ct = inputs.attr.data.classTag
    output(o.attr, inputs.attr.rdd.mapValues(_.toString))
  }
}

object VertexAttributeToDouble {
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: VertexAttributeInput[String])
      extends MagicOutput(instance) {
    val attr = vertexAttribute[Double](inputs.vs.entity)
  }
}
case class VertexAttributeToDouble()
    extends TypedMetaGraphOp[VertexAttributeInput[String], VertexAttributeToDouble.Output] {
  import VertexAttributeToDouble._
  @transient override lazy val inputs = new VertexAttributeInput[String]
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    output(o.attr, inputs.attr.rdd.flatMapValues(str =>
      if (str.nonEmpty) Some(str.toDouble) else None))
  }
}

object VertexAttributeCast {
  class Output[From, To: TypeTag](
    implicit instance: MetaGraphOperationInstance, inputs: VertexAttributeInput[From])
      extends MagicOutput(instance) {
    val attr = vertexAttribute[To](inputs.vs.entity)
  }
}
abstract class VertexAttributeCast[From, To]()
    extends TypedMetaGraphOp[VertexAttributeInput[From], VertexAttributeCast.Output[From, To]] {
  import VertexAttributeCast._
  @transient override lazy val inputs = new VertexAttributeInput[From]
  def outputMeta(instance: MetaGraphOperationInstance) = new Output[From, To]()(tt, instance, inputs)
  implicit def tt: TypeTag[To]

  def execute(inputDatas: DataSet,
              o: Output[From, To],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    implicit val ct = inputs.attr.data.classTag
    output(o.attr, inputs.attr.rdd.mapValues(_.asInstanceOf[To]))
  }
}

case class VertexAttributeVectorToAny[From]() extends VertexAttributeCast[Vector[From], Vector[Any]] {
  implicit val tt = typeTag[Vector[Any]]
}
