plugins {
  id("me.champeau.jmh")
  id("java-library")
}

apply(from = "$rootDir/gradle/java.gradle")

val minimumBranchCoverage by extra(0.6)
val minimumInstructionCoverage by extra(0.8)
val excludedClassesCoverage by extra(
  listOf(
    "datadog.telemetry.TelemetryRunnable.ThreadSleeperImpl",
    "datadog.telemetry.HostInfo",
    "datadog.telemetry.HostInfo.Os",
    "datadog.telemetry.dependency.LocationsCollectingTransformer",
    "datadog.telemetry.dependency.JbossVirtualFileHelper",
    "datadog.telemetry.RequestBuilder.NumberJsonAdapter",
    "datadog.telemetry.RequestBuilderSupplier",
    "datadog.telemetry.TelemetrySystem",
    "datadog.telemetry.api.*",
    "datadog.telemetry.metric.CiVisibilityMetricPeriodicAction"
  )
)
val excludedClassesBranchCoverage by extra(
  listOf(
    "datadog.telemetry.PolymorphicAdapterFactory.1",
    "datadog.telemetry.HostInfo",
    "datadog.telemetry.HostInfo.Os"
  )
)
val excludedClassesInstructionCoverage by extra(emptyList<String>())

dependencies {
  implementation(libs.slf4j)

  implementation(project(":internal-api"))
  
  // Antithesis SDK for assertions and property testing - bundled in tracer JAR
  implementation(group = "com.antithesis", name = "sdk", version = "1.4.5")

  compileOnly(project(":dd-java-agent:agent-tooling"))
  testImplementation(project(":dd-java-agent:agent-tooling"))
  testImplementation(project(":dd-java-agent:agent-logging"))

  compileOnly(project(":communication"))
  testImplementation(project(":communication"))

  compileOnly(project(":utils:container-utils"))
  testImplementation(project(":utils:container-utils"))

  api(libs.okhttp)
  api(libs.moshi)

  testImplementation(project(":utils:test-utils"))
  testImplementation(group = "org.jboss", name = "jboss-vfs", version = "3.2.16.Final")
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}
