import com.typesafe.sbt.packager.Keys.bashScriptExtraDefines

name := "biggraph"

javaOptions in Test := Seq(
  "-Dsun.io.serialization.extendedDebugInfo=true",
  "-Dbiggraph.default.partitions.per.core=1",
  "-XX:PermSize=256M",
   "-Xmx50000M")

scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked", "-Xfatal-warnings")

version := "0.1-SNAPSHOT"

sources in doc in Compile := List()  // Disable doc generation.

publishArtifact in packageSrc := false  // Don't package source.

scalaVersion := "2.10.6"

val sparkVersion = SettingKey[String]("spark-version", "The version of Spark used for building.")

sparkVersion := IO.readLines(baseDirectory.value / "conf/SPARK_VERSION")(0)

libraryDependencies ++= Seq(
  anorm, // Play library for making SQL queries.
  ws, // Play library for making HTTP requests.
  filters, // Play library for compressing HTTP responses.
  // These jackson deps are needed to resolve some jackson version conflict by forcing to use 2.4.4
  "com.fasterxml.jackson.core" % "jackson-core" % "2.4.4",
  "com.fasterxml.jackson.core" % "jackson-annotations" % "2.4.4",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.4.4",
  // The below dep is needed to avoid jar version conflict when running in Amazon EMR.
  // (There we use a Hadoop-less Spark build and use Hadoop libs provided by Amazon.
  // This way we get s3 consistent view support.)
  "org.apache.httpcomponents" % "httpclient" % "4.5.1",
  "org.apache.commons" % "commons-lang3" % "3.3",
  "org.apache.spark" %% "spark-core" % sparkVersion.value % "provided",
  "org.mindrot" % "jbcrypt" % "0.3m",  // For password hashing.
  "org.mozilla" % "rhino" % "1.7.7",
  "org.scalatest" %% "scalatest" % "2.1.5" % "test",
  "org.apache.spark" %% "spark-mllib" % sparkVersion.value % "provided",
  "org.apache.spark" %% "spark-hive" % sparkVersion.value % "provided",
  // Provides HyperLogLogPlus counters. Must be the same version that is
  // used by Spark.
  "com.clearspring.analytics" % "stream" % "2.7.0",
  // JDBC drivers.
  "mysql" % "mysql-connector-java" % "5.1.34",
  "org.postgresql" % "postgresql" % "9.3-1102-jdbc41",
  "org.xerial" % "sqlite-jdbc" % "3.8.7",
  // Groovy is used for workflows and the batch API.
  "org.kohsuke" % "groovy-sandbox" % "1.10",
  "com.lihaoyi" % "ammonite-sshd" % "0.5.2" cross CrossVersion.full,
  // CSV DataFrame API. Added just for use with the SSH shell, but may get used more widely later.
  "com.databricks" % "spark-csv_2.10" % "1.3.0",
  // Hive import seems to need this.
  "com.hadoop.gplcompression" % "hadoop-lzo" % "0.4.19",
  "com.google.guava" % "guava" % "16.0.1",
  // For SPARK-10306.
  "org.scala-lang" % "scala-library" % "2.10.3",
  // This is a dependency of Spark. Needed here explicitly
  // so that SetupMetricsSingleton compiles.
  "org.eclipse.jetty" % "jetty-servlet" % "8.1.19.v20160209")

resolvers += "Twitter Repository" at "http://maven.twttr.com"

// Runs "stage", then creates the "stage/version" file.
def myStage = Command.command("stage") { state =>
  import sys.process._
  val res = Project.extract(state).runTask(com.typesafe.sbt.packager.Keys.stage, state)._1
  val date = java.util.Calendar.getInstance.getTime
  val user = util.Properties.userName
  val branch = "git rev-parse --abbrev-ref HEAD".!!
  val modified = if ("git status --porcelain".!!.nonEmpty) "modified" else "mint"
  val lastCommit = "git log -1 --oneline".!!
  IO.write(new java.io.File("stage/version"), s"Staged at $date by $user from $modified $branch (at $lastCommit)\n")
  res
}

commands += myStage

// Save logs to a file. Do not run benchmarks by default. (Use "sbt bench:test" to run them.)
testOptions in Test := Seq(Tests.Argument("-fWDF", "logs/sbttest.out", "-l", "Benchmark"))

scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(scalariform.formatter.preferences.AlignParameters, true)
  .setPreference(scalariform.formatter.preferences.DoubleIndentClassDeclaration, true)

// Separate config for benchmarks.
lazy val Benchmark = (config("bench") extend Test)

inConfig(Benchmark)(Defaults.testTasks) ++ Seq(
  testOptions in Benchmark := Seq(Tests.Argument("-n", "Benchmark"))
)

lazy val root = project.in(file("."))
  .enablePlugins(PlayScala)
  .configs(Benchmark)

bashScriptExtraDefines ++= IO.readLines(baseDirectory.value / "tools" / "call_spark_submit.sh")

def dirContents(baseDir: File, dirs: String*) = {
  val subDir = dirs.foldLeft(baseDir) { (file, dir) => file / dir}
  val pathFinder = subDir * "*"
  pathFinder.get map {
    tool: File =>
      tool -> (dirs.mkString("/") + "/" + tool.getName)
  }
}

mappings in Universal ++= dirContents(baseDirectory.value, "tools")

mappings in Universal ++= dirContents(baseDirectory.value, "kitescripts")

mappings in Universal ++= dirContents(baseDirectory.value, "kitescripts", "big_data_tests")

mappings in Universal ++= dirContents(baseDirectory.value, "kitescripts", "gen_test_data")

mappings in Universal ++= dirContents(baseDirectory.value, "tools", "monitoring")

mappings in Universal ++= dirContents(baseDirectory.value, "tools", "monitoring", "dashboards")

mappings in Universal ++= dirContents(baseDirectory.value, "kitescripts", "spark_tests")

mappings in Universal ++= dirContents(baseDirectory.value, "tools", "performance_collection")
