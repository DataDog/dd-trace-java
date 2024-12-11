plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(libs.bundles.junit5)
  implementation(libs.testcontainers)
  implementation(libs.okhttp)
  implementation(libs.moshi)
}
