plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
}
description = "Shared Feature Flags parsing, state, and evaluation"

// Agent JAR indexing routes whole packages. The injection assembly must not also contain the
// parser's com.datadog.featureflag package, which belongs to the Feature Flags subsystem.
val evaluatorJar by tasks.registering(Jar::class) {
  archiveClassifier = "evaluator"
  from(sourceSets.main.get().output)
  include("com/datadog/featureflag/core/**")
}
configurations.create("evaluatorElements") {
  isCanBeConsumed = true
  isCanBeResolved = false
  outgoing.artifact(evaluatorJar)
}

dependencies {
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  implementation(libs.moshi)
  implementation(libs.slf4j)
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
}
