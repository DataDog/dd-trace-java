plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(project(":components:context:context-api"))
  implementation(libs.slf4j)
  testImplementation(libs.bundles.junit5)
}
