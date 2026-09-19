plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
}
description = "Shared Feature Flags parsing, state, and evaluation"
dependencies {
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  implementation(libs.moshi)
  implementation(libs.slf4j)
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
}
