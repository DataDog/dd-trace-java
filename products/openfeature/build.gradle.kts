import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import groovy.lang.Closure

plugins {
  `java-library`
  idea
  id("com.gradleup.shadow")
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

val shaded by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

val shadowJar by configurations.creating

dependencies {
  implementation("dev.openfeature:sdk:1.18.2")

  compileOnly(libs.slf4j)
  compileOnly(libs.moshi)
  compileOnly(libs.okhttp)
  compileOnly(libs.jctools)
  compileOnly(project(":internal-api"))
  compileOnly(project(":communication"))

  shaded(libs.slf4j)
  shaded(libs.moshi)
  shaded(libs.okhttp)
  shaded(libs.jctools)
  shaded(project(":internal-api"))
  shaded(project(":communication"))

  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
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

tasks {
  build {
    dependsOn("shadowJar")
  }

  shadowJar {
    configurations = listOf(shaded)

    dependencies {
      exclude(dependency("dev.openfeature:.*"))
      exclude(dependency("org.snakeyaml:.*"))
      exclude(dependency("com.github.jnr:.*"))
      exclude(dependency("org.ow2.asm:.*"))
    }

    relocate("datadog.communication", "datadog.openfeature.shaded.communication")
    relocate("datadog.remoteconfig", "datadog.openfeature.shaded.remoteconfig")
    relocate("datadog.trace.api.internal", "datadog.openfeature.shaded.internal")
    relocate("org.slf4j", "datadog.openfeature.shaded.slf4j")
    relocate("com.squareup.moshi", "datadog.openfeature.shaded.moshi")
    relocate("okhttp3", "datadog.openfeature.shaded.okhttp3")
    relocate("okio", "datadog.openfeature.shaded.okio")
    relocate("org.jctools", "datadog.openfeature.shaded.jctools")
    relocate("cafe.cryptography", "datadog.openfeature.shaded.cafe.cryptography")
  }
}

artifacts {
  add(shadowJar.name, tasks.named("shadowJar"))
}
