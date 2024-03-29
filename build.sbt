lazy val scala211Version = "2.11.12"
lazy val scala212Version = "2.12.10"
lazy val sparkVersion = "2.4.5"

lazy val commonSettings = Seq(
  organization := "com.google.cloud.spark",
  version := "0.16.2",
  scalaVersion := scala211Version,
  crossScalaVersions := Seq(scala211Version, scala212Version),
  test in assembly := {}  // only configuring it in the commonSettings , it can work
)

// For https://github.com/GoogleCloudPlatform/spark-bigquery-connector/issues/72
// Based on
lazy val root = (project in file("."))
  .disablePlugins(AssemblyPlugin)
  .settings(commonSettings, skip in publish := true)
  .aggregate(connector, fatJar, published)
  //.aggregate(connector, fatJar)

lazy val connector = (project in file("connector"))
  .enablePlugins(BuildInfoPlugin)
  .configs(ITest)
  .settings(
    commonSettings,
    publishSettings,
    name := "spark-bigquery",
    unmanagedSourceDirectories in Compile += baseDirectory.value /
      "third_party/apache-spark/src/main/java",
    inConfig(ITest)(Defaults.testTasks),
    testOptions in Test := Seq(Tests.Filter(unitFilter)),
    testOptions in ITest := Seq(Tests.Filter(itFilter)),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.google.cloud.spark.bigquery",
    resourceGenerators in Compile += Def.task {
      val file = (resourceManaged in Compile).value / "spark-bigquery-connector.properties"
      IO.write(file, s"scala.version=${scalaVersion.value}\n")
      Seq(file)
    }.taskValue,
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "provided",
      "org.slf4j" % "slf4j-api" % "1.7.25" % "provided",
      "org.codehaus.jackson" % "jackson-core-asl" % "1.9.13" % "provided",
      "org.codehaus.jackson" % "jackson-mapper-asl" % "1.9.13" % "provided",
      "org.apache.arrow" % "arrow-vector" % "0.16.0",
      "com.google.inject" % "guice" % "4.2.3",

      // Keep com.google.cloud dependencies in sync
      "com.google.cloud" % "google-cloud-bigquery" % "1.116.1",
      "com.google.cloud" % "google-cloud-bigquerystorage" % "0.133.2-beta",
      // Keep in sync with com.google.cloud
      "io.grpc" % "grpc-alts" % "1.29.0",
      "io.grpc" % "grpc-netty-shaded" % "1.29.0",
      "com.google.api" % "gax-grpc" % "1.56.0",
      "com.google.guava" % "guava" % "29.0-jre",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.11.0",
      "com.fasterxml.jackson.module" % "jackson-module-paranamer" % "2.11.0",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.11.0",

      "io.netty" % "netty-all" % "4.1.27.Final" % "provided",

      // runtime
      // scalastyle:off
      "com.google.cloud.bigdataoss" % "gcs-connector" % "hadoop2-2.0.0" % "runtime" classifier("shaded"),
      // scalastyle:on
      // test
      "org.scalatest" %% "scalatest" % "3.1.0" % "test",
      "org.mockito" %% "mockito-scala-scalatest" % "1.10.0" % "test",

      "org.apache.spark" %% "spark-avro" % sparkVersion % "test",
      "com.google.truth" % "truth" % "1.0.1" % "test")
      .map(_.excludeAll(excludedOrgs.map(ExclusionRule(_)): _*))
    ,
    assemblyMergeStrategy in assembly := {
      case "module-info.class" => MergeStrategy.discard
      case "git.properties" => MergeStrategy.discard

      case "mozilla/public-suffix-list.txt" => MergeStrategy.discard
      case "META-INF/io.netty.versions.properties" => MergeStrategy.discard
      case "com/google/cloud/hadoop/util/AccessTokenProvider$AccessToken.class" => MergeStrategy.discard
      case "com/google/cloud/hadoop/util/AccessTokenProvider.class" => MergeStrategy.discard
      case x => (assemblyMergeStrategy in assembly).value(x)
    }
  )

lazy val fatJar = project
  .enablePlugins(AssemblyPlugin)
  .settings(
    commonSettings,
    skip in publish := true,
    assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
    assemblyShadeRules in assembly := (
      notRenamed.map(prefix => ShadeRule.rename(s"$prefix**" -> s"$prefix@1"))
        ++: renamed.map(prefix => ShadeRule.rename(s"$prefix**" -> s"$relocationPrefix.$prefix@1"))
      ).map(_.inAll),

    assemblyMergeStrategy in assembly := {
      case "module-info.class" => MergeStrategy.discard
      case PathList(ps@_*) if ps.last.endsWith(".properties") => MergeStrategy.filterDistinctLines
      case PathList(ps@_*) if ps.last.endsWith(".proto") => MergeStrategy.discard
      // Relocate netty-tcnative.so. This is necessary even though gRPC shades it, because we shade
      // gRPC.
      case PathList("META-INF", "native", f) if f.contains("netty_tcnative") => RelocationMergeStrategy(
        path =>
          path.replace("native/lib", s"native/lib${relocationPrefix.replace('.', '_')}_"))

      // Relocate GRPC service registries
      case PathList("META-INF", "services", _) => ServiceResourceMergeStrategy(renamed,
        relocationPrefix)
      case x => (assemblyMergeStrategy in assembly).value(x)
    }
  )
  .dependsOn(connector)


lazy val published = project
  .settings(
    commonSettings,
    publishSettings,
    resolvers += Resolver.mavenLocal,
    name := "spark-bigquery-with-dependencies",
    packageBin in Compile := (assembly in(fatJar, Compile)).value
  )

lazy val myPackage = "com.google.cloud.spark.bigquery"
lazy val relocationPrefix = s"$myPackage.repackaged"

// Exclude dependencies already on Spark's Classpath
val excludedOrgs = Seq(
  // All use commons-cli:1.4
  "commons-cli",
  // Not a runtime dependency
  "com.google.auto.value",
  // All use jsr305:3.0.0
  "com.google.code.findbugs",
  "javax.annotation",
  // Spark Uses 2.9.9 google-cloud-core uses 2.9.2
  "com.sun.jdmk",
  "com.sun.jmx",
  "javax.activation",
  "javax.jms",
  "javax.mail"
)

lazy val renamed = Seq(
  "avro.shaded",
  "com.fasterxml",
  "com.google",
  "com.thoughtworks.paranamer",
  "com.typesafe",
  "io.grpc",
  "io.netty",
  "io.opencensus",
  "org.apache.arrow",
  "io.perfmark",
  "org.apache.commons",
  "org.apache.http",
  "org.checkerframework",
  "org.codehaus.mojo",
  "org.conscrypt",
  "org.json",
  "org.threeten",
  "org.tukaani.xz",
  "org.xerial.snappy")
lazy val notRenamed = Seq(myPackage)

// Default IntegrationTest config uses separate test directory, build files
lazy val ITest = config("it") extend Test
// Run scalastyle automatically
(test in Test) := ((test in Test) dependsOn scalastyle.in(Test).toTask("")).value
parallelExecution in ITest := false

def unitFilter(name: String): Boolean = (name endsWith "Suite") && !itFilter(name)

def itFilter(name: String): Boolean = name endsWith "ITSuite"

lazy val publishSettings = Seq(
  homepage := Some(url("https://github.com/GoogleCloudPlatform/spark-bigquery-connector")),
  scmInfo := Some(ScmInfo(url("https://github.com/GoogleCloudPlatform/spark-bigquery-connector"),
    "git@github.com:GoogleCloudPlatform/spark-bigquery-connector.git")),
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
  publishMavenStyle := true,
  pomExtra :=
    <developers>
      <developer>
        <organization>Google LLC</organization>
        <organizationUrl>http://www.google.com</organizationUrl>
      </developer>
    </developers>,

  publishTo := Some(if (version.value.trim.endsWith("SNAPSHOT")) {
    Opts.resolver.sonatypeSnapshots
  } else {
    Opts.resolver.sonatypeStaging
  })
)
