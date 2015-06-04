// Export to SQL databases through JDBC.
package com.lynxanalytics.biggraph.graph_util

import java.sql
import org.apache.commons.lang.StringEscapeUtils
import org.apache.spark.rdd.RDD
import scala.reflect.runtime.universe._
import scala.language.existentials
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.spark_util.SortedRDD
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }

import org.apache.spark.sql.Row
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.types.LongType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.apache.spark.SparkContext

object SQLExport {
  private val SimpleIdentifier = "[a-zA-Z0-9_]+".r
  def quoteIdentifier(s: String) = {
    s match {
      case SimpleIdentifier() => s
      case _ => '"' + s.replaceAll("\"", "\"\"") + '"'
    }
  }
  private def addRDDs(base: SortedRDD[ID, Seq[_]], rdds: Seq[SortedRDD[ID, _]]): RDD[Row] = {
    rdds.foldLeft(base) { (seqs, rdd) =>
      seqs
        .sortedLeftOuterJoin(rdd)
        .mapValues { case (seq, opt) => seq :+ opt.getOrElse(null) }
    }.values.map { seq => Row.fromSeq(seq) }
  }

  private def makeInserts(quotedTable: String, rdd: RDD[Seq[String]]) = {
    rdd.mapPartitions { it =>
      it.map(seq =>
        s"INSERT INTO $quotedTable VALUES (" + seq.mkString(", ") + ");")
    }
  }

  private def execute(db: String, update: String) = {
    val connection = sql.DriverManager.getConnection("jdbc:" + db)
    val statement = connection.createStatement()
    statement.executeUpdate(update);
    connection.close()
  }

  private val supportedTypes: Seq[(Type, DataType)] = Seq(
    (typeOf[Double], DoubleType),
    (typeOf[String], StringType),
    (typeOf[Long], LongType))

  private case class SQLColumn(name: String, sqlType: DataType, rdd: SortedRDD[ID, _], nullable: Boolean)

  private def sqlAttribute[T](name: String, attr: Attribute[T])(implicit dm: DataManager) = {
    val opt = supportedTypes.find(line => line._1 =:= attr.typeTag.tpe)
    assert(opt.nonEmpty, s"Attribute '$name' is of an unsupported type: ${attr.typeTag}")
    val (tpe, sqlType) = opt.get
    SQLColumn(name, sqlType, attr.rdd, /* nullable = */ true)
  }

  def apply(
    table: String,
    vertexSet: VertexSet,
    attributes: Map[String, Attribute[_]])(implicit dataManager: DataManager): SQLExport = {
    for ((name, attr) <- attributes) {
      assert(attr.vertexSet == vertexSet, s"Attribute $name is not for vertex set $vertexSet")
    }
    new SQLExport(dataManager.sqlContext, table, vertexSet.rdd, attributes.toSeq.sortBy(_._1).map {
      case (name, attr) => sqlAttribute(name, attr)
    })
  }

  def apply(
    table: String,
    edgeBundle: EdgeBundle,
    attributes: Map[String, Attribute[_]],
    srcColumnName: String = "srcVertexId",
    dstColumnName: String = "dstVertexId")(implicit dataManager: DataManager): SQLExport = {
    for ((name, attr) <- attributes) {
      assert(attr.vertexSet == edgeBundle.idSet,
        s"Attribute $name is not for edge bundle $edgeBundle")
    }
    new SQLExport(dataManager.sqlContext, table, edgeBundle.idSet.rdd, Seq(
      // The src and dst vertex ids are mandatory.
      SQLColumn(srcColumnName, LongType, edgeBundle.rdd.mapValues(_.src), /* nullable = */ false),
      SQLColumn(dstColumnName, LongType, edgeBundle.rdd.mapValues(_.dst), /* nullable = */ false)
    ) ++ attributes.toSeq.sortBy(_._1).map { case (name, attr) => sqlAttribute(name, attr) })
  }
}
import SQLExport._
class SQLExport private (
    sqlContext: SQLContext,
    table: String,
    vertexSet: VertexSetRDD,
    sqls: Seq[SQLColumn]) {

  private val schema =
    StructType(sqls.map(sql => StructField(sql.name, sql.sqlType, sql.nullable)))
  private val rowRDD = addRDDs(
    vertexSet.mapValues(_ => Nil),
    sqls.map(_.rdd))
  private val dataFrame = sqlContext.createDataFrame(rowRDD, schema)

  def insertInto(db: String, delete: Boolean) = {
    if (delete) {
      dataFrame.createJDBCTable("jdbc:" + db, quoteIdentifier(table), true)
    } else {
      dataFrame.insertIntoJDBC("jdbc:" + db, quoteIdentifier(table), false)
    }
  }
}
