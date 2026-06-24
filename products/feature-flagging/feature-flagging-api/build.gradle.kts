import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.lang.Closure

plugins {
  `java-library`
  idea
  `maven-publish`
}

apply(from = "$rootDir/gradle/java.gradle")
apply(from = "$rootDir/gradle/publish.gradle")

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_11)
}

description = "Implementation of the OpenFeature Provider interface."

// Set both JAR and Maven artifact name
val openFeatureArtifactId = "dd-openfeature"
base {
  archivesName.set(openFeatureArtifactId)
}

publishing {
  publications.withType<MavenPublication>().configureEach {
    artifactId = openFeatureArtifactId
  }
}

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
  api("dev.openfeature:sdk:1.20.1")

  compileOnly(project(":products:feature-flagging:feature-flagging-bootstrap"))
  compileOnly(project(":utils:config-utils"))
  compileOnly("io.opentelemetry:opentelemetry-api:1.47.0")
  compileOnly("io.opentelemetry:opentelemetry-sdk-metrics:1.47.0")
  compileOnly("io.opentelemetry:opentelemetry-exporter-otlp:1.47.0")

  testImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  testImplementation("io.opentelemetry:opentelemetry-api:1.47.0")
  testImplementation("io.opentelemetry:opentelemetry-sdk-metrics:1.47.0")
  testImplementation("io.opentelemetry:opentelemetry-exporter-otlp:1.47.0")
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.moshi)
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.47.0")
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
