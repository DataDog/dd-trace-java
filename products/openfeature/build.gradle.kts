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

val excludedClassesCoverage by extra(
  listOf(
    // Feature lags POJOs
    "datadog.trace.api.openfeature.exposure.ExposureCache.Key",
    "datadog.trace.api.openfeature.exposure.ExposureCache.Value",
    "datadog.trace.api.openfeature.exposure.dto.Allocation",
    "datadog.trace.api.openfeature.exposure.dto.ExposureEvent",
    "datadog.trace.api.openfeature.exposure.dto.ExposuresRequest",
    "datadog.trace.api.openfeature.exposure.dto.Flag",
    "datadog.trace.api.openfeature.exposure.dto.Subject",
    "datadog.trace.api.openfeature.exposure.dto.Variant",
    "datadog.trace.api.openfeature.config.ufc.v1.Allocation",
    "datadog.trace.api.openfeature.config.ufc.v1.ConditionConfiguration",
    "datadog.trace.api.openfeature.config.ufc.v1.ConditionOperator",
    "datadog.trace.api.openfeature.config.ufc.v1.Environment",
    "datadog.trace.api.openfeature.config.ufc.v1.Flag",
    "datadog.trace.api.openfeature.config.ufc.v1.Rule",
    "datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration",
    "datadog.trace.api.openfeature.config.ufc.v1.Shard",
    "datadog.trace.api.openfeature.config.ufc.v1.ShardRange",
    "datadog.trace.api.openfeature.config.ufc.v1.Split",
    "datadog.trace.api.openfeature.config.ufc.v1.ValueType",
    "datadog.trace.api.openfeature.config.ufc.v1.Variant",
  )
)

dependencies {
  api("dev.openfeature:sdk:1.18.2")
  api(libs.slf4j)
  api(libs.moshi)
  api(libs.okhttp)
  api(libs.jctools)

  compileOnly(project(":remote-config:remote-config-api"))
  compileOnly(project(":communication"))
  compileOnly(project(":internal-api"))

  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
  testImplementation(project(":remote-config:remote-config-api"))
  testImplementation(project(":communication"))
  testImplementation(project(":internal-api"))
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
