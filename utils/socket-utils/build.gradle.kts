import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import groovy.lang.Closure
import org.gradle.kotlin.dsl.extra

plugins {
  `java-library`
  idea
}

val minJavaVersionForTests by extra(JavaVersion.VERSION_17)

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(libs.slf4j)
  implementation(project(":internal-api"))
  implementation(libs.jnr.unixsocket)
  testImplementation(files(sourceSets["main_java17"].output))
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain_java17") {
  failOnMissingClasses = false
}

fun AbstractCompile.setJavaVersion(javaVersionInteger: Int, unsetReleaseFlag: Boolean) {
  (project.extra.get("setJavaVersion") as Closure<*>).call(this, javaVersionInteger, unsetReleaseFlag)
}

listOf("compileMain_java17Java", "compileTestJava").forEach {
  tasks.named<JavaCompile>(it) {
    setJavaVersion(17, true)
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
  }
}

idea {
  module {
    jdkName = "17"
  }
}
