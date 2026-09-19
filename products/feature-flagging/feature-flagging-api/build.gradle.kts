import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension

plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
  id("me.champeau.jmh")
}
description = "Unbundled OpenFeature adapter shared by the standalone and agent assemblies."
configure<TestJvmConstraintsExtension> { minJavaVersion.set(JavaVersion.VERSION_11) }
java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }
dependencies {
  api("dev.openfeature:sdk:1.20.1")
  implementation(project(":products:feature-flagging:feature-flagging-core"))
  implementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  implementation(project(":products:feature-flagging:feature-flagging-config"))
  implementation(project(":utils:config-utils"))
  implementation(libs.slf4j)
  compileOnly("io.opentelemetry:opentelemetry-api:1.57.0")
  compileOnly("org.projectlombok:lombok:1.18.38")
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.moshi)
  testImplementation("io.opentelemetry:opentelemetry-api:1.57.0")
  jmhImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  jmhImplementation(project(":products:feature-flagging:feature-flagging-config"))
  jmhImplementation(project(":utils:config-utils"))
}
jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}
