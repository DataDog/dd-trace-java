plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
}

description = "Feature flagging agent backend: remote config, event proxy and span enrichment"

dependencies {
  api(libs.slf4j)
  api(project(":communication"))
  implementation(project(":internal-api"))
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  compileOnly(project(":products:feature-flagging:feature-flagging-config"))

  // Platform JSON writer for the ffe_* tag values.
  compileOnly(project(":components:json"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":products:feature-flagging:feature-flagging-config"))
  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
}
