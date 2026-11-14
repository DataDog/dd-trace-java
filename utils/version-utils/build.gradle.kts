plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.internal-library")
  id("dd-trace-java.conventions.errorprone")
}

dependencies {
  implementation(libs.slf4j)
}
