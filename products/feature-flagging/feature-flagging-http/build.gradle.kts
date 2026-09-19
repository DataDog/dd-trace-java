plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
  id("me.champeau.jmh")
}
description = "Direct Feature Flags configuration and HTTP transport adapters."
dependencies {
  api(project(":products:feature-flagging:feature-flagging-lib"))
  api(project(":products:feature-flagging:feature-flagging-core"))
  implementation(project(":products:feature-flagging:feature-flagging-config"))
  implementation(project(":internal-api"))
  implementation(project(":communication"))
  implementation(project(":utils:logging-utils"))
  implementation(libs.moshi)
  implementation(libs.slf4j)
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
}
