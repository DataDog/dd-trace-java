plugins {
  `java-library`
}

description = "Metrics API"

apply(from = rootDir.resolve("gradle/java.gradle"))

dependencies {
  implementation(libs.slf4j)
}
