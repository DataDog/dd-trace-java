plugins {
  `java-library`
  id("dd-trace-java.module.internal-api")
  id("dd-trace-java.jmh-conventions")
}

description = "Metrics API"

dependencies {
  implementation(libs.slf4j)
  api(project(":components:environment"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.jol.core)
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
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
