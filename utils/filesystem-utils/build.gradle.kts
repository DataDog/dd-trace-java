plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  testImplementation(project(":utils:test-utils"))
}
