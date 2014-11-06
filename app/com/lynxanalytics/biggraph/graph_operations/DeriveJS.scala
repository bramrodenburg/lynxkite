package com.lynxanalytics.biggraph.graph_operations

import scala.reflect.runtime.universe._
import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util.RDDUtils

object DeriveJS {
  class Input(attrCount: Int)
      extends MagicInputSignature {
    val vs = vertexSet
    val attrs = (0 until attrCount).map(i => vertexAttribute[JSValue](vs, Symbol("attr-" + i)))
  }
  class Output[T: TypeTag](implicit instance: MetaGraphOperationInstance,
                           inputs: Input) extends MagicOutput(instance) {
    val attr = vertexAttribute[T](inputs.vs.entity)
  }
  def add(a: VertexAttribute[Double],
          b: VertexAttribute[Double])(implicit manager: MetaGraphManager): VertexAttribute[Double] = {
    import Scripting._
    val op = DeriveJSDouble(JavaScript("a + b"), Seq("a", "b"))
    op(op.attrs, Seq(a, b).map(VertexAttributeToJSValue.run[Double])).result.attr
  }
  def negative(x: VertexAttribute[Double])(implicit manager: MetaGraphManager): VertexAttribute[Double] = {
    import Scripting._
    val op = DeriveJSDouble(JavaScript("-x"), Seq("x"))
    op(op.attrs, Seq(x).map(VertexAttributeToJSValue.run[Double])).result.attr
  }
}
import DeriveJS._
abstract class DeriveJS[T](
  expr: JavaScript,
  attrNames: Seq[String])
    extends TypedMetaGraphOp[Input, Output[T]] {
  implicit def tt: TypeTag[T]
  override val isHeavy = true
  @transient override lazy val inputs = new Input(attrNames.size)
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(tt, instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output[T],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val joined = {
      val noAttrs = inputs.vs.rdd.mapValues(_ => new Array[JSValue](attrNames.size))
      inputs.attrs.zipWithIndex.foldLeft(noAttrs) {
        case (rdd, (attr, idx)) =>
          rdd.sortedJoin(attr.rdd).mapValues {
            case (attrs, attr) => {
              attrs(idx) = attr
              attrs
            }
          }
      }
    }
    val derived = joined.flatMapValues {
      case values =>
        val namedValues = attrNames.zip(values).toMap.mapValues(_.value)
        // JavaScript's "undefined" is returned as a Java "null".
        Option(expr.evaluate(namedValues).asInstanceOf[T])
    }
    output(o.attr, derived)
  }
}

case class DeriveJSString(
  expr: JavaScript,
  attrNames: Seq[String])
    extends DeriveJS[String](expr, attrNames) {
  @transient lazy val tt = typeTag[String]
}

case class DeriveJSDouble(
  expr: JavaScript,
  attrNames: Seq[String])
    extends DeriveJS[Double](expr, attrNames) {
  @transient lazy val tt = typeTag[Double]
}
