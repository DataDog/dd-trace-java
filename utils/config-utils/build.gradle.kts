plugins {
  `java-library`
  `java-test-fixtures`
  id("dd-trace-java.supported-config-generator")
}

apply(from = "$rootDir/gradle/java.gradle")

extra["minimumBranchCoverage"] = 0.7
extra["minimumInstructionCoverage"] = 0.7

extra["excludedClassesCoverage"] = listOf(
  "datadog.trace.api.ConfigCollector",
  "datadog.trace.api.env.CapturedEnvironment",
  "datadog.trace.api.env.CapturedEnvironment.ProcessInfo",
  // tested in internal-api
  "datadog.trace.api.telemetry.OtelEnvMetricCollectorProvider",
  "datadog.trace.api.telemetry.ConfigInversionMetricCollectorProvider",
  "datadog.trace.bootstrap.config.provider.civisibility.CiEnvironmentVariables",
  "datadog.trace.bootstrap.config.provider.CapturedEnvironmentConfigSource",
  "datadog.trace.bootstrap.config.provider.ConfigConverter.ValueOfLookup",
  // tested in internal-api
  "datadog.trace.bootstrap.config.provider.ConfigProvider",
  "datadog.trace.bootstrap.config.provider.ConfigProvider.ConfigMergeResolver",
  "datadog.trace.bootstrap.config.provider.ConfigProvider.ConfigValueResolver",
  "datadog.trace.bootstrap.config.provider.ConfigProvider.Singleton",
  "datadog.trace.bootstrap.config.provider.ConfigProvider.Source",
  "datadog.trace.bootstrap.config.provider.EnvironmentConfigSource",
  "datadog.trace.bootstrap.config.provider.MapConfigSource",
  // tested in internal-api
  "datadog.trace.bootstrap.config.provider.OtelEnvironmentConfigSource",
  "datadog.trace.bootstrap.config.provider.stableconfig.Selector",
  // tested in internal-api
  "datadog.trace.bootstrap.config.provider.StableConfigParser",
  "datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource",
  "datadog.trace.config.inversion.SupportedConfiguration"
)

extra["excludedClassesBranchCoverage"] = listOf(
  "datadog.trace.bootstrap.config.provider.AgentArgsInjector",
  // Enum
  "datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy",
  "datadog.trace.util.ConfigStrings"
)

extra["excludedClassesInstructionCoverage"] = listOf(
  "datadog.trace.api.telemetry.NoOpConfigInversionMetricCollector",
  "datadog.trace.config.inversion.GeneratedSupportedConfigurations",
  "datadog.trace.config.inversion.SupportedConfigurationSource"
)

dependencies {
  compileOnly(project(":components:annotations"))
  implementation(project(":components:environment"))
  implementation(project(":dd-trace-api"))
  api(project(":utils:filesystem-utils"))
  implementation(libs.slf4j)
  implementation("org.snakeyaml", "snakeyaml-engine", "2.9")

  testFixturesImplementation(libs.junit.jupiter)

  testImplementation(project(":utils:test-utils"))
  testImplementation("org.snakeyaml:snakeyaml-engine:2.9")
  testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.legacy.get()}")
}
