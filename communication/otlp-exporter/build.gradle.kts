plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

description = "otlp-exporter"

// Network/IO classes are hard to unit-test without a real OTLP endpoint; they are exercised
// via integration tests and the profiling-otel smoke tests instead.
extra["excludedClassesCoverage"] = listOf(
  "datadog.communication.otlp.OtlpGrpcSender",
  "datadog.communication.otlp.OtlpHttpSender",
  "datadog.communication.otlp.OtlpResponse",
  "datadog.communication.otlp.OtlpSenderSupport"
)

dependencies {
  api(project(":dd-trace-api"))
  api(project(":communication"))
  implementation(project(":utils:logging-utils"))
  implementation(libs.slf4j)

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.okhttp3.mockwebserver)
}
