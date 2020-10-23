name := "play-28-smoke-test"
organization := "com.datadoghq"
maintainer := "no.body@no.company"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := System.getProperty("scala.version", "2.13.2")
scalacOptions += "-target:8"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

target := file(System.getProperty("datadog.smoketest.builddir", baseDirectory.value.getAbsolutePath)) / "target"

libraryDependencies ++= Seq(
  guice,
  playCore,
  javaCore,
  nettyServer,
  akkaHttpServer,
  ws,
  javaWs
)

val otGroup = "io.opentracing"
val otVersion = "0.32.0"

libraryDependencies ++= Seq (
  otGroup % "opentracing-api" % otVersion,
  otGroup % "opentracing-util" % otVersion
)

csrConfiguration ~= { conf =>
  conf.withParallelDownloads(1)
}
