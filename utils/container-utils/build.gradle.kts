plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(project(":components:environment"))
  implementation(libs.slf4j)

  testImplementation(project(":utils:test-utils"))
}
