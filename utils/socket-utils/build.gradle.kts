import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import groovy.lang.Closure
import org.gradle.kotlin.dsl.extra

plugins {
  `java-library`
  idea
}

apply(from = "$rootDir/gradle/java.gradle")

extensions.getByName("tracerJava").withGroovyBuilder {
  this.invokeMethod("addSourceSetFor", arrayOf(JavaVersion.VERSION_17, true))
}

dependencies {
  implementation(libs.slf4j)
  implementation(project(":internal-api"))
  implementation(libs.jnr.unixsocket)
  testImplementation(files(sourceSets["main_java17"].output))
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain_java17") {
  failOnMissingClasses = false
}

fun AbstractCompile.configureCompiler(javaVersionInteger: Int, compatibilityVersion: JavaVersion? = null, unsetReleaseFlagReason: String? = null) {
  (project.extra["configureCompiler"] as Closure<*>).call(this, javaVersionInteger, compatibilityVersion, unsetReleaseFlagReason)
}

listOf("compileMain_java17Java", "compileTestJava").forEach {
  tasks.named<JavaCompile>(it) {
    configureCompiler(17, JavaVersion.VERSION_1_8)
  }
}

idea {
  module {
    jdkName = "17"
  }
}
