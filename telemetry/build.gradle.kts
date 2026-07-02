plugins {
  id("me.champeau.jmh")
  id("java-library")
}

apply(from = "$rootDir/gradle/java.gradle")

extra["minimumBranchCoverage"] = 0.6
extra["minimumInstructionCoverage"] = 0.8
extra["excludedClassesCoverage"] = listOf(
  "datadog.telemetry.TelemetryRunnable.ThreadSleeperImpl",
  "datadog.telemetry.HostInfo",
  "datadog.telemetry.HostInfo.Os",
  "datadog.telemetry.dependency.LocationsCollectingTransformer",
  "datadog.telemetry.dependency.JbossVirtualFileHelper",
  "datadog.telemetry.RequestBuilder.NumberJsonAdapter",
  "datadog.telemetry.RequestBuilderSupplier",
  "datadog.telemetry.TelemetrySystem",
  "datadog.telemetry.api.*",
  "datadog.telemetry.metric.CiVisibilityMetricPeriodicAction",
  "datadog.telemetry.metric.OtelSpiMetricPeriodicAction"
)
extra["excludedClassesBranchCoverage"] = listOf(
  "datadog.telemetry.PolymorphicAdapterFactory.1",
  "datadog.telemetry.HostInfo",
  "datadog.telemetry.HostInfo.Os"
)
extra["excludedClassesInstructionCoverage"] = emptyList<String>()

dependencies {
  implementation(libs.slf4j)

  implementation(project(":internal-api"))

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
  testImplementation(libs.bundles.mockito)
  testImplementation(group = "org.jboss", name = "jboss-vfs", version = "3.2.16.Final")
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}
