plugins {
  `java-library`
}

description = "StatsD client"

apply(from = rootDir.resolve("gradle/java.gradle"))

// TODO Try to clean up as many dependencies as possible after migrating the tests
dependencies {
  api(project(":products:metrics-api"))
  implementation(libs.slf4j)
  implementation(libs.dogstatsd)
  implementation(project(":internal-api"))
  implementation(project(":utils:filesystem-utils"))

  implementation(group = "com.datadoghq", name = "sketches-java", version = "0.8.3")

  testImplementation(project(":utils:test-utils"))
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bytebuddy)
  testImplementation("org.msgpack:msgpack-core:0.8.20")
  testImplementation("org.msgpack:jackson-dataformat-msgpack:0.8.20")
  testImplementation(
    group = "com.squareup.okhttp3",
    name = "mockwebserver",
    version = libs.versions.okhttp.legacy.get() // actually a version range
  )
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
