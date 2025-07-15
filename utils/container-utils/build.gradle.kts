plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(libs.slf4j)
  implementation(project(":components:environment"))

  testImplementation(project(":utils:test-utils"))
}
