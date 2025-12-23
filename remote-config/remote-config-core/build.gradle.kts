plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

val minimumBranchCoverage by extra(0.6)
val minimumInstructionCoverage by extra(0.8)
val excludedClassesCoverage by extra(
  listOf(
    // not used yet
    "datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.AgentInfo",
    // only half the adapter interface used
    "datadog.remoteconfig.tuf.InstantJsonAdapter",
    // idem
    "datadog.remoteconfig.tuf.RawJsonAdapter",
    "datadog.remoteconfig.ExceptionHelper",
  )
)
val excludedClassesBranchCoverage by extra(
  listOf(
    "datadog.remoteconfig.tuf.FeaturesConfig",
    "datadog.remoteconfig.PollerRequestFactory",
  )
)
val excludedClassesInstructionCoverage by extra(
  listOf(
    "datadog.remoteconfig.ConfigurationChangesListener.PollingHinterNoop",
  )
)

dependencies {
  api(project(":remote-config:remote-config-api"))

  implementation(libs.slf4j)
  implementation(libs.okhttp)
  implementation(libs.moshi)
  implementation(libs.bundles.cafe.crypto)

  // Antithesis SDK for assertions and property testing - bundled in tracer JAR
  implementation(group = "com.antithesis", name = "sdk", version = "1.4.5")

  implementation(project(":internal-api"))

  testImplementation(project(":utils:test-utils"))
}
