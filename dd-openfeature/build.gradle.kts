import datadog.gradle.configureCompiler
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension

plugins {
  `java-library`
  idea
  id("dd-trace-java.conventions.java")
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
  api("dev.openfeature:sdk:1.22.0")
  api(libs.jackson.core)

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
}

tasks.withType<JavaCompile>().configureEach {
  configureCompiler(JavaVersion.VERSION_11)
}
