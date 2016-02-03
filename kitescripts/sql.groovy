// Demonstration for using the DataFrames API on project RDDs.
p = lynx.newProject()
p.exampleGraph()
p.edgeDF.show()
p.vertexDF.printSchema()
p.vertexDF.show()
p.vertexDF.groupBy('gender').count().show()
p.vertexDF.registerTempTable('example')
df = lynx.sql('select * from example where gender = "Male"')
df.show()
df.printSchema()
df.write().format('com.databricks.spark.csv')
  .option('header', 'true').mode('overwrite').save('males.csv')
df.write().format('parquet').mode('overwrite').save('males.parquet')
df.write().format('json').mode('overwrite').save('males.json')
df = lynx.sqlContext.read().format('json').load('males.json')
df.show()
df.printSchema()
