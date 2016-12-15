// Creates a new attribute by evaluating a JavaScript expression over other attributes.
package com.lynxanalytics.biggraph.graph_operations

import scala.reflect.runtime.universe._

import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.JavaScriptEvaluator
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object DeriveJS {
  class Input(attrCount: Int, scalarCount: Int)
      extends MagicInputSignature {
    val vs = vertexSet
    val attrs = (0 until attrCount).map(i => vertexAttribute[JSValue](vs, Symbol("attr-" + i)))
    val scalars = (0 until scalarCount).map(i => scalar[JSValue](Symbol("scalar-" + i)))
  }
  class Output[T: TypeTag](implicit instance: MetaGraphOperationInstance,
                           inputs: Input) extends MagicOutput(instance) {
    val attr = vertexAttribute[T](inputs.vs.entity)
  }
  def negative(x: Attribute[Double])(implicit manager: MetaGraphManager): Attribute[Double] = {
    import Scripting._
    val op = DeriveJSDouble(JavaScript("-x"), Seq("x"))
    op(op.attrs, Seq(x).map(VertexAttributeToJSValue.run[Double])).result.attr
  }

  def deriveFromAttributes[T: TypeTag](
    exprString: String,
    namedAttributes: Seq[(String, Attribute[_])],
    vertexSet: VertexSet,
    namedScalars: Seq[(String, Scalar[_])] = Seq(),
    onlyOnDefinedAttrs: Boolean = true)(
      implicit manager: MetaGraphManager): Attribute[T] = {

    // Check name collision between scalars and attributes
    val common =
      namedAttributes.map(_._1).toSet & namedScalars.map(_._1).toSet
    assert(common.isEmpty, {
      val collisions = common.mkString(",")
      s"Identical scalar and attribute name: $collisions." +
        s" Please rename either the scalar or the attribute."
    })

    val js = JavaScript(exprString)

    // Good to go, let's prepare the attributes for DeriveJS.
    val jsValueAttributes =
      namedAttributes.map { case (_, attr) => VertexAttributeToJSValue.run(attr) }

    val jsValueScalars =
      namedScalars.map { case (_, sclr) => ScalarToJSValue.run(sclr) }

    val op: DeriveJS[T] =
      if (typeOf[T] =:= typeOf[String]) {
        DeriveJSString(js, namedAttributes.map(_._1), namedScalars.map(_._1), onlyOnDefinedAttrs).asInstanceOf[DeriveJS[T]]
      } else if (typeOf[T] =:= typeOf[Double]) {
        DeriveJSDouble(js, namedAttributes.map(_._1), namedScalars.map(_._1), onlyOnDefinedAttrs).asInstanceOf[DeriveJS[T]]
      } else ???

    val defaultAttributeValues =
      namedAttributes.map { case (_, attr) => JSValue.defaultValue(attr.typeTag).value }
    val defaultScalarValues =
      namedScalars.map { case (_, sc) => JSValue.defaultValue(sc.typeTag).value }
    op.validateJS[T](defaultAttributeValues, defaultScalarValues)

    import Scripting._
    op(op.vs, vertexSet)(op.attrs, jsValueAttributes)(op.scalars, jsValueScalars).result.attr
  }
}
import DeriveJS._
abstract class DeriveJS[T](
  expr: JavaScript,
  attrNames: Seq[String],
  scalarNames: Seq[String],
  onlyOnDefinedAttrs: Boolean)
    extends TypedMetaGraphOp[Input, Output[T]] {
  implicit def resultTypeTag: TypeTag[T]
  implicit def resultClassTag: reflect.ClassTag[T]
  override val isHeavy = true
  @transient override lazy val inputs = new Input(attrNames.size, scalarNames.size)
  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(resultTypeTag, instance, inputs)

  // Validate JS using default values for the types of the attributes.
  def validateJS[T: TypeTag](
    defaultAttributeValues: Seq[Any],
    defaultScalarValues: Seq[Any]): Unit = {
    val testNamedValues =
      (attrNames ++ scalarNames).zip(defaultAttributeValues ++ defaultScalarValues).toMap
    evaluate(expr.evaluator, testNamedValues)
  }

  def execute(inputDatas: DataSet,
              o: Output[T],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    import com.lynxanalytics.biggraph.spark_util.UniqueSortedRDD
    val joined: UniqueSortedRDD[ID, Array[JSValue]] = {
      val noAttrs = inputs.vs.rdd.mapValues(_ => new Array[JSValue](attrNames.size))
      if (onlyOnDefinedAttrs) {
        inputs.attrs.zipWithIndex.foldLeft(noAttrs) {
          case (rdd, (attr, idx)) =>
            rdd.sortedJoin(attr.rdd).mapValues {
              case (attrs, attr) =>
                attrs(idx) = attr
                attrs
            }
        }
      } else {
        inputs.attrs.zipWithIndex.foldLeft(noAttrs) {
          case (rdd, (attr, idx)) =>
            rdd.sortedLeftOuterJoin(attr.rdd).mapValues {
              case (attrs, attr) =>
                // Have to pass undefined explicitly to Rhino to avoid surprise conversions.
                attrs(idx) = attr.getOrElse(JSValue(org.mozilla.javascript.Undefined.instance))
                attrs
            }
        }
      }
    }

    val scalars = inputs.scalars.map { _.value }.toArray
    val allNames = attrNames ++ scalarNames

    val derived = joined.mapPartitions({ it =>
      val evaluator = expr.evaluator
      it.flatMap {
        case (key, values) =>
          val namedValues = allNames.zip(values ++ scalars).toMap.mapValues(_.value)
          evaluate(evaluator, namedValues).map {
            result => key -> checkJSResult(result, expr.contextString(namedValues))
          }
      }
    }, preservesPartitioning = true).asUniqueSortedRDD
    output(o.attr, derived)
  }

  protected def evaluate(evaluator: JavaScriptEvaluator, mapping: Map[String, Any]): Option[T]
  protected def checkJSResult(
    v: T, // The value to convert.
    context: => String): T // The context of the conversion for detailed error messages.
}

object DeriveJSString extends OpFromJson {
  private val scalarNamesParameter = NewParameter[Seq[String]]("scalarNames", Seq())
  private val onlyOnDefinedAttrsParameter = NewParameter[Boolean]("onlyOnDefinedAttrs", true)
  def fromJson(j: JsValue) =
    DeriveJSString(JavaScript(
      (j \ "expr").as[String]),
      (j \ "attrNames").as[Seq[String]],
      scalarNamesParameter.fromJson(j),
      onlyOnDefinedAttrsParameter.fromJson(j))
}
case class DeriveJSString(
  expr: JavaScript,
  attrNames: Seq[String],
  scalarNames: Seq[String] = Seq(),
  onlyOnDefinedAttrs: Boolean = true)
    extends DeriveJS[String](expr, attrNames, scalarNames, onlyOnDefinedAttrs) {
  @transient lazy val resultTypeTag = typeTag[String]
  @transient lazy val resultClassTag = reflect.classTag[String]
  override def toJson = Json.obj(
    "expr" -> expr.expression,
    "attrNames" -> attrNames) ++
    DeriveJSString.scalarNamesParameter.toJson(scalarNames) ++
    DeriveJSString.onlyOnDefinedAttrsParameter.toJson(onlyOnDefinedAttrs)
  def checkJSResult(v: String, context: => String): String = v
  def evaluate(evaluator: JavaScriptEvaluator, mapping: Map[String, Any]): Option[String] = {
    evaluator.evaluateString(mapping)
  }
}

object DeriveJSDouble extends OpFromJson {
  private val scalarNamesParameter = NewParameter[Seq[String]]("scalarNames", Seq())
  private val onlyOnDefinedAttrsParameter = NewParameter[Boolean]("onlyOnDefinedAttrs", true)
  def fromJson(j: JsValue) =
    DeriveJSDouble(JavaScript(
      (j \ "expr").as[String]),
      (j \ "attrNames").as[Seq[String]],
      scalarNamesParameter.fromJson(j),
      onlyOnDefinedAttrsParameter.fromJson(j))
}
case class DeriveJSDouble(
  expr: JavaScript,
  attrNames: Seq[String],
  scalarNames: Seq[String] = Seq(),
  onlyOnDefinedAttrs: Boolean = true)
    extends DeriveJS[Double](expr, attrNames, scalarNames, onlyOnDefinedAttrs) {
  @transient lazy val resultTypeTag = typeTag[Double]
  @transient lazy val resultClassTag = reflect.classTag[Double]
  override def toJson = Json.obj(
    "expr" -> expr.expression,
    "attrNames" -> attrNames) ++
    DeriveJSDouble.scalarNamesParameter.toJson(scalarNames) ++
    DeriveJSDouble.onlyOnDefinedAttrsParameter.toJson(onlyOnDefinedAttrs)
  def checkJSResult(v: Double, context: => String): Double = {
    assert(!v.isNaN(), s"$context did not return a number: $v")
    assert(!v.isInfinite(), s"$context returned an infinite number: $v")
    v
  }
  def evaluate(evaluator: JavaScriptEvaluator, mapping: Map[String, Any]): Option[Double] = {
    evaluator.evaluateDouble(mapping)
  }
}
