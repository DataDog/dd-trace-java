plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

description = "otlp-exporter"

extra["excludedClassesCoverage"] = listOf(
  "datadog.communication.otlp.OtlpGrpcSender",
  "datadog.communication.otlp.OtlpHttpSender",
  "datadog.communication.otlp.OtlpResponse",
  "datadog.communication.otlp.OtlpSenderSupport"
)

dependencies {
  api(project(":dd-trace-api"))
  api(project(":communication"))
  api(project(":internal-api"))
  implementation(project(":utils:logging-utils"))
  implementation(libs.slf4j)

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.okhttp3.mockwebserver)
}
