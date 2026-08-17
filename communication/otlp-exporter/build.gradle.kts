plugins {
  `java-library`
}

description = "otlp-exporter"

apply(from = rootDir.resolve("gradle/java.gradle"))

dependencies {
  api(project(":dd-trace-api"))
  api(project(":communication"))
  implementation(project(":utils:logging-utils"))
  implementation(libs.slf4j)

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.legacy.get()}")
}
