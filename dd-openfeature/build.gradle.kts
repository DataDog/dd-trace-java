import datadog.gradle.configureCompiler
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension

plugins {
  `java-library`
  idea
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
  api("dev.openfeature:sdk:1.22.0")
  api("io.opentelemetry:opentelemetry-api:1.66.0")
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

// The dd-openfeature provider jar is not produced by the CI `build` job, so there is no reference
// artifact to compare against. Disable the release jar comparison gate registered by publish.gradle.
tasks.named("compareToReferenceJar") {
  enabled = false
}
