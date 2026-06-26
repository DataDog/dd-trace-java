plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

extra["excludedClassesCoverage"] = listOf(
  "datadog.trace.api.time.ControllableTimeSource:",
  "datadog.trace.api.time.SystemTimeSource"
)

dependencies {
  testImplementation(project(":utils:test-utils"))
}
