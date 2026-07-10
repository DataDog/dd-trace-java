plugins {
  `java-library`
  id("dd-trace-java.version-file")
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(libs.slf4j)
}
