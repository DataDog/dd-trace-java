import datadog.gradle.configureCompiler
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import me.champeau.gradle.japicmp.JapicmpTask

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

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

dependencies {
  api("dev.openfeature:sdk:1.23.0")
  implementation("io.opentelemetry:opentelemetry-api:1.66.0")
  api(libs.jackson.core)

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
  manifest {
    attributes("Automatic-Module-Name" to "com.datadog.openfeature")
  }
}

// The agent bridge SPI versions, with the first SDK release shipping each of them. A released
// version is frozen (only default methods can be added): check it against that release.
// TODO APMLP-1512 Set the v1 baseline to 1.69.0, the first SDK release with the agent bridge,
//  once it is published.
val bridgeSpiBaselines = mapOf<String, String?>(
  "v1" to null,
)
bridgeSpiBaselines.forEach { (spi, baseline) ->
  if (baseline != null) {
    val baselineSdk = "com.datadoghq:dd-openfeature:$baseline"
    val check = tasks.register<JapicmpTask>("japicmpBridge${spi.uppercase()}") {
      description = "Checks the agent bridge SPI $spi stays compatible with dd-openfeature $baseline."
      oldArchives.from(configurations.detachedConfiguration(dependencies.create("$baselineSdk@jar")))
      oldClasspath.from(configurations.detachedConfiguration(dependencies.create(baselineSdk)))
      newArchives.from(tasks.named<Jar>("jar"))
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
  newArchives.from(tasks.named<Jar>("jar"))
  newClasspath.from(configurations.named("runtimeClasspath"))
  packageIncludes.addAll("com.datadog.openfeature", "datadog.trace.api.openfeature")
  failOnModification.set(true)
  failOnSourceIncompatibility.set(true)
  // TODO APMLP-1512 Remove once the baseline is 1.69.0 or later: earlier releases link the agent
  //  bootstrap API.
  ignoreMissingClasses.set(true)
  txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/public-api.txt"))
}
tasks.named("check") {
  dependsOn(japicmpPublicApi)
}
