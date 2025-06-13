plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")
// We do not publish separate jar, but having version file is useful
apply(from = "$rootDir/gradle/version.gradle")

dependencies {
  implementation(libs.slf4j)
}
