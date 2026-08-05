import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.lang.Closure

plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Provider-owned Feature Flagging CDN transport and polling"

// Transport failures and concurrent lifecycle races add defensive branches.
// Integration tests cover success, timeout, cancellation, retry, and shutdown behavior.
extra["minimumBranchCoverage"] = 0.7
extra["minimumInstructionCoverage"] = 0.8

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_11)
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

dependencies {
  api(project(":products:feature-flagging:feature-flagging-core"))

  testImplementation(libs.bundles.junit5)
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
