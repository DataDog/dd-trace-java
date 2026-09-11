import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.lang.Closure
import org.gradle.api.plugins.jvm.JvmTestSuite

plugins {
  `java-library`
  idea
  id("dd-trace-java.module.distributable.api")
  id("me.champeau.jmh")
}

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_11)
}

description = "Implementation of the OpenFeature Provider interface."

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
  api("dev.openfeature:sdk:1.20.1")

  compileOnly(project(":products:feature-flagging:feature-flagging-bootstrap"))
  compileOnly(project(":products:feature-flagging:feature-flagging-config"))
  compileOnly(project(":utils:config-utils"))
  compileOnly("io.opentelemetry:opentelemetry-api:1.47.0")

  testImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  testImplementation(project(":utils:config-utils"))
  testImplementation("io.opentelemetry:opentelemetry-api:1.47.0")
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.moshi)

  // The main source set gets the bootstrap/config types as compileOnly, so the JMH source set
  // needs them on its own compile and runtime classpath to drive the hook end to end.
  jmhImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  jmhImplementation(project(":products:feature-flagging:feature-flagging-config"))
  jmhImplementation(project(":utils:config-utils"))
}

testing {
  suites {
    register<JvmTestSuite>("legacyOpenFeatureSdkTest") {
      dependencies {
        implementation(project())
      }
    }
  }
}

// Compile the compatibility test against the supported API, then replace only its runtime SDK
// with the last unsupported release so it exercises the real return-type linkage failure.
configurations.named("legacyOpenFeatureSdkTestRuntimeClasspath") {
  resolutionStrategy.force("dev.openfeature:sdk:1.15.1")
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
  if (project.hasProperty("jmhIncludes")) {
    includes = listOf(project.property("jmhIncludes").toString())
  }
  if (project.hasProperty("jmhProf")) {
    profilers = listOf(project.property("jmhProf").toString())
  }
}

fun AbstractCompile.configureCompiler(
  javaVersionInteger: Int,
  compatibilityVersion: JavaVersion? = null,
  unsetReleaseFlagReason: String? = null
) {
  (project.extra["configureCompiler"] as Closure<*>).call(
    this,
    javaVersionInteger,
    compatibilityVersion,
    unsetReleaseFlagReason
  )
}

tasks.withType<JavaCompile>().configureEach {
  configureCompiler(11, JavaVersion.VERSION_11)
}

tasks.withType<Javadoc>().configureEach {
  javadocTool = javaToolchains.javadocToolFor(java.toolchain)
}

// The dd-openfeature provider jar is not produced by the CI `build` job, so there is no reference
// artifact to compare against. Disable the release jar comparison gate registered by publish.gradle.
tasks.named("compareToReferenceJar") {
  enabled = false
}
