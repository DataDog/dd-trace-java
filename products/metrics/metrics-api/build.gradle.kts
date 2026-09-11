plugins {
  `java-library`
  id("dd-trace-java.module.internal-api")
}

description = "Metrics API"

dependencies {
  implementation(libs.slf4j)

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
}

extra["excludedClassesCoverage"] = listOf(
  "datadog.metrics.api.Monitoring",
  "datadog.metrics.api.NoOpCounter",
  "datadog.metrics.api.NoOpHistogram",
  "datadog.metrics.api.NoOpHistogramsFactory",
  "datadog.metrics.api.NoOpMonitoring",
  "datadog.metrics.api.NoOpRecording",
  "datadog.metrics.api.statsd.NoOpStatsDClient",
)
