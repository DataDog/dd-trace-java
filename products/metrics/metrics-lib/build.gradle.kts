import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  `java-library`
  id("com.gradleup.shadow")
  id("dd-trace-java.module.agent-product")
}

description = "StatsD client"

dependencies {
  api(project(":products:metrics:metrics-api"))
  implementation(libs.slf4j)
  implementation(libs.dogstatsd)
  implementation(project(":internal-api"))
  implementation(project(":utils:filesystem-utils"))
  implementation(project(":utils:logging-utils"))

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

extra["minimumBranchCoverage"] = 0.5
extra["minimumInstructionCoverage"] = 0.8
extra["excludedClassesCoverage"] = listOf(
  "datadog.communication.monitor.DDAgentStatsDConnection",
  "datadog.communication.monitor.DDAgentStatsDConnection.*",
  "datadog.communication.monitor.LoggingStatsDClient",
)
// val excludedClassesBranchCoverage by extra(
//   listOf(
//   )
// )
// val excludedClassesInstructionCoverage by extra(
//   listOf(
//   )
// )
