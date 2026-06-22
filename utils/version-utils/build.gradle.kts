plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.internal-library")
}

dependencies {
  implementation(libs.slf4j)
}
