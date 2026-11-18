import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import datadog.gradle.configureCompiler
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import me.champeau.gradle.japicmp.JapicmpTask
import java.util.zip.ZipFile

plugins {
  `java-library`
  id("dd-trace-java.module.distributable.api")
  id("dd-trace-java.version-file")
  id("dd-trace-java.jmh-conventions")
}

description = "Datadog OpenFeature provider SDK."

// Set both JAR and Maven artifact name
val openFeatureArtifactId = "dd-openfeature"
base {
  archivesName.set(openFeatureArtifactId)
}

publishing {
  publications.withType<MavenPublication>().configureEach {
    artifactId = openFeatureArtifactId
  }
}

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_11)
}

// TODO: Cover these classes, then remove them from the coverage baseline (APMLP-2264)
extra["excludedClassesBranchCoverage"] = listOf(
  "com.datadog.openfeature.DebugLoggingHook",
  "com.datadog.openfeature.FlagEvalLoggingHook",
  "com.datadog.openfeature.FlagEvalMetrics",
  "com.datadog.openfeature.FlagEvalMetricsHook",
  "com.datadog.openfeature.Provider",
  "com.datadog.openfeature.SpanEnrichmentHook",
  "com.datadog.openfeature.internal.EventPipeline",
  "com.datadog.openfeature.internal.JsonWriting",
  "com.datadog.openfeature.internal.config.SdkVersion",
  "com.datadog.openfeature.internal.delivery.CdnConfigurationSource",
  "com.datadog.openfeature.internal.delivery.DirectIntakeTransport",
  "com.datadog.openfeature.internal.delivery.HttpCall",
  "com.datadog.openfeature.internal.delivery.RemoteConfigurationService",
  "com.datadog.openfeature.internal.delivery.Retries",
  "com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache.Key",
  "com.datadog.openfeature.internal.exposure.ExposurePipeline.ExposureSerializingHandler",
  "com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent",
  "com.datadog.openfeature.internal.flagevaluation.FlagEvaluationPipeline",
)
extra["excludedClassesInstructionCoverage"] = listOf(
  "com.datadog.openfeature.FlagEvalMetrics",
  "com.datadog.openfeature.SpanEnrichmentHook",
  "com.datadog.openfeature.internal.JsonWriting",
  "com.datadog.openfeature.internal.bridge.v1.HealthMetrics",
  "com.datadog.openfeature.internal.bridge.v1.SpanEnricher.1",
  "com.datadog.openfeature.internal.config.SdkVersion",
  "com.datadog.openfeature.internal.delivery.HttpCall",
  "com.datadog.openfeature.internal.delivery.HttpCall.CancellableBodySubscriber",
  "com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache.Sent",
  "com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent",
  "com.datadog.openfeature.internal.flagevaluation.FlagEvaluationPipeline.SerializingHandlerForTest",
  "datadog.trace.api.openfeature.Provider",
)

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

val toolbox by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  api("dev.openfeature:sdk:1.23.0")
  implementation("io.opentelemetry:opentelemetry-api:1.66.0")
  api(libs.jackson.core)

  // Baked into the published jar (see toolboxJar below), so it never appears in the POM.
  compileOnly(project(":components:toolbox"))
  toolbox(project(":components:toolbox"))
  testImplementation(project(":components:toolbox"))
  "jmhImplementation"(project(":components:toolbox"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
}

jmh {
  jmhVersion = libs.versions.jmh.get()
}

tasks.withType<JavaCompile>().configureEach {
  configureCompiler(JavaVersion.VERSION_11)
}

tasks.withType<Javadoc>().configureEach {
  javadocTool = javaToolchains.javadocToolFor(java.toolchain)
}

tasks.named<Jar>("jar") {
  destinationDirectory = layout.buildDirectory.dir("libs-unbundled")
  archiveClassifier = "unbundled"
  manifest {
    attributes("Automatic-Module-Name" to "com.datadog.openfeature")
  }
}

// The SDK has no dependencies of its own: the toolbox primitives it uses are relocated into its
// own package, so they never collide with the attached agent's copy, and only the classes the
// SDK reaches are kept. The Shadow plugin is not applied, so the publication and its POM stay
// those of the java component; only the jar it publishes is replaced.
val toolboxRelocationPrefix = "com.datadog.openfeature.internal.shaded"
val toolboxJar = tasks.register<ShadowJar>("toolboxJar") {
  description = "Builds the published jar, with the toolbox classes it uses relocated into it."
  group = "build"
  archiveBaseName = openFeatureArtifactId
  archiveClassifier = ""
  destinationDirectory = layout.buildDirectory.dir("libs")
  from(sourceSets.main.map { it.output })
  configurations.set(listOf(toolbox))
  relocate("datadog.trace.util", "$toolboxRelocationPrefix.datadog.trace.util")
  relocate("datadog.trace.api.function", "$toolboxRelocationPrefix.datadog.trace.api.function")
  relocate("datadog.contract", "$toolboxRelocationPrefix.datadog.contract")
  minimize()
  manifest {
    attributes("Automatic-Module-Name" to "com.datadog.openfeature")
  }
  duplicatesStrategy = DuplicatesStrategy.FAIL
}
listOf("apiElements", "runtimeElements").forEach { name ->
  configurations.named(name) {
    outgoing.artifacts.clear()
    outgoing.artifact(toolboxJar)
  }
}
tasks.named("assemble") {
  dependsOn(toolboxJar)
}

// Every toolbox class in the published jar must be relocated: an unrelocated one would be shared
// with whatever agent version is attached and break the SDK's forward-compatibility contract.
val checkToolboxRelocation = tasks.register("checkToolboxRelocation") {
  description = "Checks that the published jar holds no unrelocated toolbox classes."
  group = "verification"
  val archive = toolboxJar.flatMap { it.archiveFile }
  inputs.file(archive)
  doLast {
    val leaked = ZipFile(archive.get().asFile).use { zip ->
      zip.entries().asSequence().map { it.name }
        .filter {
          it.startsWith("datadog/trace/util/") || it.startsWith("datadog/contract/") ||
            it.startsWith("datadog/trace/api/function/")
        }
        .toList()
    }
    check(leaked.isEmpty()) { "Unrelocated toolbox classes in ${archive.get().asFile.name}: $leaked" }
  }
}
tasks.named("check") {
  dependsOn(checkToolboxRelocation)
}

// The agent bridge SPI versions, with the first SDK release shipping each of them. A released
// version is frozen (only default methods can be added): check it against that release.
val bridgeSpiBaselines = mapOf<String, String?>(
  "v1" to "1.69.0",
)
bridgeSpiBaselines.forEach { (spi, baseline) ->
  if (baseline != null) {
    val baselineSdk = "com.datadoghq:dd-openfeature:$baseline"
    val check = tasks.register<JapicmpTask>("japicmpBridge${spi.uppercase()}") {
      description = "Checks the agent bridge SPI $spi stays compatible with dd-openfeature $baseline."
      oldArchives.from(configurations.detachedConfiguration(dependencies.create("$baselineSdk@jar")))
      oldClasspath.from(configurations.detachedConfiguration(dependencies.create(baselineSdk)))
      newArchives.from(toolboxJar)
      newClasspath.from(configurations.named("runtimeClasspath"))
      packageIncludes.add("com.datadog.openfeature.internal.bridge.$spi")
      // The agent implements the SPI: an abstract method added to an interface breaks it.
      failOnModification.set(true)
      failOnSourceIncompatibility.set(true)
      txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/bridge-$spi.txt"))
    }
    tasks.named("check") {
      dependsOn(check)
    }
  }
}

// The public API, outside the internal packages, must stay compatible with the previous release:
// each release keeps the API of all the previous ones. The previous release is the latest one
// before the version being built, so release branches compare against their own line.
val publicApiBaseline = "(,${version.toString().removeSuffix("-SNAPSHOT")})"
val japicmpPublicApi = tasks.register<JapicmpTask>("japicmpPublicApi") {
  description = "Checks the public API stays compatible with the previous dd-openfeature release."
  val baselineSdk = "com.datadoghq:dd-openfeature:$publicApiBaseline"
  oldArchives.from(configurations.detachedConfiguration(dependencies.create("$baselineSdk@jar")))
  oldClasspath.from(configurations.detachedConfiguration(dependencies.create(baselineSdk)))
  newArchives.from(toolboxJar)
  newClasspath.from(configurations.named("runtimeClasspath"))
  packageIncludes.addAll("com.datadog.openfeature", "datadog.trace.api.openfeature")
  failOnModification.set(true)
  failOnSourceIncompatibility.set(true)
  txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/public-api.txt"))
}
tasks.named("check") {
  dependsOn(japicmpPublicApi)
}

// The shared FFE fixtures live outside any source set, so Gradle neither copies nor packages them:
// the tests and benchmarks read them from disk, and the smoke tests copy the files they need.
val fixtures = layout.projectDirectory.dir("src/test/fixtures")

tasks.withType<Test>().configureEach {
  inputs.dir(fixtures).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}

// ScaleSuiteTest checks the case files that ScaleSuiteBenchmark lists in its @Param. It reads the
// annotation by reflection, as the test source set cannot compile against the jmh one.
tasks.named<Test>("test") {
  classpath += sourceSets["jmh"].output + configurations["jmhRuntimeClasspath"]
}

// The scale benchmarks read the fixtures from disk at run time, so a fixture change reruns them. A
// file tree rather than a directory: the suites are missing when the submodule is not initialized.
tasks.named("jmh") {
  inputs.files(
    fixtures.dir("ffe-system-test-data").asFileTree.matching { include("evaluation-suites/**") }
  ).withPropertyName("scaleSuites").withPathSensitivity(PathSensitivity.RELATIVE)
}
