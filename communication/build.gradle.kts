plugins {
  `java-library`
}

description = "communication"

apply(from = rootDir.resolve("gradle/java.gradle"))

dependencies {
  implementation(libs.slf4j)

  api(project(":remote-config:remote-config-api"))
  implementation(project(":remote-config:remote-config-core"))
  implementation(project(":internal-api"))
  implementation(project(":utils:container-utils"))
  implementation(project(":utils:filesystem-utils"))
  implementation(project(":utils:socket-utils"))
  implementation(project(":utils:version-utils"))

  api(libs.okio)
  api(libs.okhttp)
  api(libs.moshi)
  // metrics-lib is needed rather than metrics-api to change the default port of StatsD connection manager
  // TODO Could help decoupling it later to only depend on metrics-api
  // Exclude sketches-java since it's bundled in metrics-agent (metrics/ directory only)
  implementation(project(":products:metrics:metrics-lib")) {
    exclude(group = "com.datadoghq", module = "sketches-java")
  }

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
    "datadog.communication.ddagent.ExternalAgentLauncher",
    "datadog.communication.ddagent.ExternalAgentLauncher.NamedPipeHealthCheck",
    "datadog.communication.ddagent.SharedCommunicationObjects.FixedConfigUrlSupplier",
    "datadog.communication.ddagent.SharedCommunicationObjects.RetryConfigUrlSupplier",
    "datadog.communication.http.OkHttpUtils",
    "datadog.communication.http.OkHttpUtils.1",
    "datadog.communication.http.OkHttpUtils.ByteBufferRequestBody",
    "datadog.communication.http.OkHttpUtils.CustomListener",
    "datadog.communication.http.OkHttpUtils.GZipByteBufferRequestBody",
    "datadog.communication.http.OkHttpUtils.GZipRequestBodyDecorator",
    "datadog.communication.http.OkHttpUtils.JsonRequestBody",
    "datadog.communication.BackendApiFactory",
    "datadog.communication.BackendApiFactory.Intake",
    "datadog.communication.EvpProxyApi",
    "datadog.communication.IntakeApi",
    "datadog.communication.util.IOUtils",
    "datadog.communication.util.IOUtils.1",
  )
)
val excludedClassesBranchCoverage by extra(
  listOf(
    "datadog.communication.ddagent.TracerVersion",
    "datadog.communication.BackendApiFactory",
    "datadog.communication.EvpProxyApi",
    "datadog.communication.IntakeApi",
  )
)
val excludedClassesInstructionCoverage by extra(
  listOf(
    // can't reach the error condition now
    "datadog.communication.fleet.FleetServiceImpl",
    "datadog.communication.ddagent.SharedCommunicationObjects",
    "datadog.communication.ddagent.TracerVersion",
    "datadog.communication.BackendApiFactory",
    "datadog.communication.BackendApiFactory.Intake",
    "datadog.communication.EvpProxyApi",
    "datadog.communication.IntakeApi",
    "datadog.communication.util.IOUtils",
    "datadog.communication.util.IOUtils.1",
  )
)
