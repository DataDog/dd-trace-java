plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

val excludedClassesCoverage by extra(
  listOf(
    "datadog.trace.api.time.ControllableTimeSource:",
    "datadog.trace.api.time.SystemTimeSource",
  ),
)

dependencies {
  testImplementation(project(":utils:test-utils"))
}
