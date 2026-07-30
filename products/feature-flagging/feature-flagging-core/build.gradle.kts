import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension

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
  "datadog.openfeature.internal.core.EvaluationResult",
  "datadog.openfeature.internal.core.EvaluationResult.*",
  "datadog.openfeature.internal.core.ApplyResult",
  "datadog.openfeature.internal.core.SourceStatus",
)

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_1_8)
}

dependencies {
  implementation(libs.moshi)
  compileOnlyApi(project(":products:feature-flagging:feature-flagging-bootstrap"))

  testImplementation(libs.bundles.junit5)
  testImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
}
