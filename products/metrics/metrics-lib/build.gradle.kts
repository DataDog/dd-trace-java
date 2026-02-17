import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  `java-library`
  id("com.gradleup.shadow")
}

description = "StatsD client"

apply(from = rootDir.resolve("gradle/java.gradle"))

dependencies {
  api(project(":products:metrics:metrics-api"))
  implementation(libs.slf4j)
  implementation(libs.dogstatsd)
  implementation(project(":internal-api"))
  implementation(project(":utils:filesystem-utils"))

  implementation(group = "com.datadoghq", name = "sketches-java", version = "0.8.3")

  testImplementation(project(":utils:test-utils"))
  testImplementation(libs.bundles.junit5)
  testImplementation(group = "com.google.protobuf", name = "protobuf-java", version = "3.14.0")
}

tasks.named<ShadowJar>("shadowJar") {
  dependencies {
    val deps = project.extra["deps"] as Map<*, *>
    val excludeShared = deps["excludeShared"] as groovy.lang.Closure<*>
    excludeShared.delegate = this
    excludeShared.call()
  }
}

val minimumBranchCoverage by extra(0.5)
val minimumInstructionCoverage by extra(0.8)
val excludedClassesCoverage by extra(
  listOf(
    "datadog.communication.monitor.DDAgentStatsDConnection",
    "datadog.communication.monitor.DDAgentStatsDConnection.*",
    "datadog.communication.monitor.LoggingStatsDClient",
  )
)
// val excludedClassesBranchCoverage by extra(
//   listOf(
//   )
// )
// val excludedClassesInstructionCoverage by extra(
//   listOf(
//   )
// )
