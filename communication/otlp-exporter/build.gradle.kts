plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

description = "otlp-exporter"

dependencies {
  api(project(":dd-trace-api"))
  api(project(":communication"))
  implementation(project(":utils:logging-utils"))
  implementation(libs.slf4j)

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.okhttp3.mockwebserver)
}
