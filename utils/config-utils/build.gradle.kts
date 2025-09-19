plugins {
  `java-library`
  id("supported-config-generator")
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(project(":components:environment"))
  implementation(project(":components:yaml"))
  implementation(project(":dd-trace-api"))
  implementation(libs.slf4j)

  testImplementation(project(":utils:test-utils"))
}
