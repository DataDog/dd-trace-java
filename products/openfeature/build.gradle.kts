import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import groovy.lang.Closure

plugins {
  `java-library`
  idea
}

apply(from = "$rootDir/gradle/java.gradle")
apply(from = "$rootDir/gradle/publish.gradle")

val minJavaVersionForTests by extra(JavaVersion.VERSION_11)

description = "dd-openfeature"

idea {
  module {
    jdkName = "11"
  }
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

dependencies {
  api(libs.slf4j)
  api("dev.openfeature:sdk:1.18.2")
  compileOnly(project(":internal-api"))

  testImplementation(project(":internal-api"))
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.moshi)
  testImplementation("org.awaitility:awaitility:4.3.0")
}

fun AbstractCompile.configureCompiler(
  javaVersionInteger: Int,
  compatibilityVersion: JavaVersion? = null,
  unsetReleaseFlagReason: String? = null
) {
  (project.extra["configureCompiler"] as Closure<*>).call(
    this,
    javaVersionInteger,
    compatibilityVersion,
    unsetReleaseFlagReason
  )
}

tasks.withType<JavaCompile>().configureEach {
  configureCompiler(11, JavaVersion.VERSION_11)
}

tasks.withType<Javadoc>().configureEach {
  javadocTool = javaToolchains.javadocToolFor(java.toolchain)
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain") {
  failOnMissingClasses = false
}

tasks.named<Jar>("jar") {
  archiveBaseName.set("dd-openfeature")
}
