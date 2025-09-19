plugins {
  `java-library`
  id("supported-config-generator")
}

apply(from = "$rootDir/gradle/java.gradle")

val minimumBranchCoverage by extra(0.7)
val minimumInstructionCoverage by extra(0.7)

val excludedClassesCoverage by extra(
  listOf(
    "datadog.trace.api.ConfigCollector",
    "datadog.trace.api.env.CapturedEnvironment",
    "datadog.trace.api.env.CapturedEnvironment.ProcessInfo",
    // tested in internal-api
    "datadog.trace.api.telemetry.OtelEnvMetricCollectorProvider",
    "datadog.trace.api.telemetry.ConfigInversionMetricCollectorProvider",
    "datadog.trace.bootstrap.config.provider.CapturedEnvironmentConfigSource",
    "datadog.trace.bootstrap.config.provider.ConfigConverter.ValueOfLookup",
    // tested in internal-api
    "datadog.trace.bootstrap.config.provider.ConfigProvider",
    "datadog.trace.bootstrap.config.provider.ConfigProvider.ConfigMergeResolver",
    "datadog.trace.bootstrap.config.provider.ConfigProvider.ConfigValueResolver",
    "datadog.trace.bootstrap.config.provider.ConfigProvider.Singleton",
    "datadog.trace.bootstrap.config.provider.ConfigProvider.Source",
    "datadog.trace.bootstrap.config.provider.EnvironmentConfigSource",
    // tested in internal-api
    "datadog.trace.bootstrap.config.provider.OtelEnvironmentConfigSource",
    "datadog.trace.bootstrap.config.provider.stableconfig.Selector",
    // tested in internal-api
    "datadog.trace.bootstrap.config.provider.StableConfigParser",
    "datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource"
  )
)

val excludedClassesBranchCoverage by extra(
  listOf(
    "datadog.trace.bootstrap.config.provider.AgentArgsInjector",
    "datadog.trace.util.ConfigStrings"
  )
)

val excludedClassesInstructionCoverage by extra(
  listOf(
    "datadog.trace.config.inversion.GeneratedSupportedConfigurations",
    "datadog.trace.config.inversion.SupportedConfigurationSource"
  )
)

dependencies {
  implementation(project(":components:environment"))
  implementation(project(":components:yaml"))
  implementation(project(":dd-trace-api"))
  implementation(libs.slf4j)

  testImplementation(project(":utils:test-utils"))
  testImplementation("org.snakeyaml:snakeyaml-engine:2.9")
}

tasks.named("javadoc") {
  dependsOn("generateSupportedConfigurations")
}
