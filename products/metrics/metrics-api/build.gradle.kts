plugins {
  `java-library`
  id("dd-trace-java.module.internal-component")
}

description = "Metrics API"

dependencies {
  implementation(libs.slf4j)
}
