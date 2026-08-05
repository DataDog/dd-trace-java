plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Agent-independent Feature Flagging telemetry state and encoding"

extra["excludedClassesCoverage"] = listOf(
  // Immutable cache key and value types
  "datadog.openfeature.internal.telemetry.ExposureDeduplicationCache.Key",
  "datadog.openfeature.internal.telemetry.ExposureDeduplicationCache.Value",
)

dependencies {
  testImplementation(libs.bundles.junit5)
}
