import datadog.gradle.configureCompiler
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  `java-library`
  idea
  id("dd-trace-java.conventions.java")
  id("dd-trace-java.version-file")
  id("dd-trace-java.jmh-conventions")
}

description = "Datadog OpenFeature provider SDK."

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_11)
}

idea {
  module {
    jdkName = "11"
  }
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

// The agent bridge SPI versions, with the first SDK release shipping each of them. A released
// version is frozen (only default methods can be added): check it against that release.
// TODO Set the v1 baseline once the first SDK release with the agent bridge is published.
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
