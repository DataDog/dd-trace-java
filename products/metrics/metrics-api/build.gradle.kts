plugins {
  `java-library`
  id("dd-trace-java.module.internal-api")
}

description = "Metrics API"

dependencies {
  implementation(libs.slf4j)
}
