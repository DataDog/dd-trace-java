plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

val excludedClassesCoverage by extra(
  listOf(
    "datadog.trace.api.time.ControllableTimeSource:",
    "datadog.trace.api.time.SystemTimeSource"
  )
)

dependencies {
  testImplementation(project(":utils:test-utils"))
}
