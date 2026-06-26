plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

extra["minimumInstructionCoverage"] = 0.8
extra["excludedClassesCoverage"] = listOf(
  "datadog.trace.test.agent.decoder.v04.raw.*",
  "datadog.trace.test.agent.decoder.v05.raw.*",
)

dependencies {
  implementation(group = "org.msgpack", name = "msgpack-core", version = "0.8.24")

  testImplementation(libs.bundles.junit5)
  testImplementation(project(":utils:test-utils"))
}
