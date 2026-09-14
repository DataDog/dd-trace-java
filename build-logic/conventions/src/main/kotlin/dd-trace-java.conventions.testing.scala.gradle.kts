import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

plugins {
  scala
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val scalaLibrary = libs.findLibrary("scala").get()

dependencies {
  add("compileOnly", scalaLibrary)
  add("testImplementation", scalaLibrary)
}

val compileTestScala = tasks.named<ScalaCompile>("compileTestScala")

pluginManager.withPlugin("groovy") {
  tasks.named<GroovyCompile>("compileTestGroovy") {
    classpath += files(compileTestScala.flatMap { it.destinationDirectory })
  }
}
