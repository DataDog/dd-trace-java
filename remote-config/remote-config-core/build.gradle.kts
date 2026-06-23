plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

extra["minimumBranchCoverage"] = 0.6
extra["minimumInstructionCoverage"] = 0.8
extra["excludedClassesCoverage"] = listOf(
  // not used yet
  "datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.AgentInfo",
  // only half the adapter interface used
  "datadog.remoteconfig.tuf.InstantJsonAdapter",
  // idem
  "datadog.remoteconfig.tuf.RawJsonAdapter",
  "datadog.remoteconfig.ExceptionHelper",
)
extra["excludedClassesBranchCoverage"] = listOf(
  "datadog.remoteconfig.tuf.FeaturesConfig",
  "datadog.remoteconfig.PollerRequestFactory",
)
extra["excludedClassesInstructionCoverage"] = listOf("datadog.remoteconfig.ConfigurationChangesListener.PollingHinterNoop",)

dependencies {
  api(project(":remote-config:remote-config-api"))

  implementation(libs.slf4j)
  implementation(libs.okhttp)
  implementation(libs.moshi)
  implementation(libs.bundles.cafe.crypto)

  implementation(project(":internal-api"))
  implementation(project(":utils:logging-utils"))

  testImplementation(project(":utils:test-utils"))
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.tabletest)
  testImplementation(libs.assertj.core)
  testImplementation(libs.json.unit.assertj)
}
