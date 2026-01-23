import groovy.lang.Closure

plugins {
  `java-library`
  idea
}

description = "communication"

apply(from = rootDir.resolve("gradle/java.gradle"))

// Manually configure Java 11 source set (like agent-bootstrap)
// Cannot use tracerJava.addSourceSetFor because it creates circular dependencies
// when Java 11 source set needs to see main source set classes
sourceSets {
  create("main_java11") {
    java.srcDirs("${project.projectDir}/src/main/java11")
  }
}

fun AbstractCompile.configureCompiler(javaVersionInteger: Int, compatibilityVersion: JavaVersion? = null, unsetReleaseFlagReason: String? = null) {
  (project.extra["configureCompiler"] as Closure<*>).call(this, javaVersionInteger, compatibilityVersion, unsetReleaseFlagReason)
}

tasks.named<JavaCompile>("compileMain_java11Java") {
  configureCompiler(11, JavaVersion.VERSION_1_8)
}

tasks.named<Jar>("jar") {
  from(sourceSets["main_java11"].output)
}

idea {
  module {
    jdkName = "11"
  }
}

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
  implementation(project(":products:metrics:metrics-lib"))

  // Java 11 source set needs access to main source set and dependencies
  "main_java11CompileOnly"(project(":internal-api"))
  "main_java11CompileOnly"(sourceSets["main"].output)

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
