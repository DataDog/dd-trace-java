plugins {
  `java-library`
  id("dd-trace-java.version-file")
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Java agent runtime for Feature Flagging configuration and telemetry"

dependencies {
  api(libs.slf4j)
  api(libs.moshi)
  api(libs.jctools)
  api(project(":communication"))
  implementation(project(":internal-api"))
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  implementation(project(":products:feature-flagging:feature-flagging-telemetry"))
  implementation(project(":utils:logging-utils"))
  api(project(":utils:queue-utils"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
}
