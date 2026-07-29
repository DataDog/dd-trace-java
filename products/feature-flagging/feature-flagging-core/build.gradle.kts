import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.lang.Closure

plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Provider-owned Feature Flagging model, parser, evaluator, and configuration state"

// Defensive parser and evaluator branches reject malformed customer input.
// The tests cover each supported operation and the main rejection paths.
extra["minimumBranchCoverage"] = 0.7

extra["excludedClassesCoverage"] = listOf(
  // Immutable data transfer types
  "datadog.openfeature.internal.core.ConfigurationSnapshot",
  "datadog.openfeature.internal.core.ConfigurationSnapshot.*",
  "datadog.openfeature.internal.core.EvaluationResult",
  "datadog.openfeature.internal.core.EvaluationResult.*",
  "datadog.openfeature.internal.core.ApplyResult",
  "datadog.openfeature.internal.core.SourceStatus",
)

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_11)
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
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

dependencies {
  implementation(libs.moshi)

  testImplementation(libs.bundles.junit5)
}
