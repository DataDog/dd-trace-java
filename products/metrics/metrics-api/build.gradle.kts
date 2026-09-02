plugins {
  `java-library`
  id("dd-trace-java.module.internal-api")
}

description = "Metrics API"

dependencies {
  implementation(libs.slf4j)
  implementation(project(":internal-api"))

  testImplementation(libs.bundles.junit5)
}
