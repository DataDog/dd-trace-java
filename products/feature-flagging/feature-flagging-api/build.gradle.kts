import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.lang.Closure

plugins {
  `java-library`
  idea
  id("com.gradleup.shadow")
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
  modules {
    module("com.squareup.okio:okio") {
      replacedBy("com.datadoghq.okio:okio")
    }
  }

  api("dev.openfeature:sdk:1.20.1")

  implementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  implementation(project(":products:feature-flagging:feature-flagging-config"))
  implementation(project(":products:feature-flagging:feature-flagging-lib"))
  implementation(project(":utils:config-utils"))
  compileOnly("io.opentelemetry:opentelemetry-api:1.47.0")
  // OpenFeature SDK classes retain @lombok.Generated in their bytecode. Supplying the annotation
  // on the analysis classpath keeps SpotBugs from treating that optional SDK build detail as a
  // missing class; Lombok is neither bundled nor published as a dependency.
  compileOnly("org.projectlombok:lombok:1.18.38")

  testImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  testImplementation(project(":utils:config-utils"))
  testImplementation("io.opentelemetry:opentelemetry-api:1.47.0")
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.moshi)
  testImplementation("org.awaitility:awaitility:4.3.0")

  // The main source set gets the bootstrap/config types as compileOnly, so the JMH source set
  // needs them on its own compile and runtime classpath to drive the hook end to end.
  jmhImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  jmhImplementation(project(":products:feature-flagging:feature-flagging-config"))
  jmhImplementation(project(":utils:config-utils"))
}

tasks.jar {
  destinationDirectory = layout.buildDirectory.dir("libs-unbundled")
  archiveClassifier = "unbundled"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
  archiveClassifier = ""

  dependencies {
    exclude(dependency("dev.openfeature:sdk:.*"))
    exclude(dependency("io.opentelemetry:.*:.*"))
    exclude(dependency("org.slf4j:.*:.*"))
    // These are optional agent capabilities reachable from the shared Config/communication
    // modules but not from standalone HTTP polling or direct EVP delivery.
    exclude(dependency("cafe.cryptography:.*:.*"))
    exclude(dependency("com.datadoghq:java-dogstatsd-client:.*"))
    exclude(dependency("com.datadoghq:sketches-java:.*"))
    exclude(dependency("com.github.jnr:.*:.*"))
    exclude(dependency("org.ow2.asm:.*:.*"))
  }

  relocate("com.datadog.featureflag.", "datadog.openfeature.internal.featureflag.")
  relocate("com.squareup.", "datadog.openfeature.internal.com.squareup.")
  relocate("okhttp3.", "datadog.openfeature.internal.okhttp3.")
  relocate("okio.", "datadog.openfeature.internal.okio.")
  relocate("org.jctools.", "datadog.openfeature.internal.org.jctools.")
  relocate("datadog.", "datadog.openfeature.internal.datadog.") {
    exclude("datadog.trace.api.featureflag.*")
    exclude("datadog.trace.api.openfeature.*")
  }

  // Keep the Feature Flagging implementation because DDEvaluator loads its standalone entrypoint
  // reflectively. Minimize the rest of the agent dependency graph to the classes that runtime
  // actually reaches instead of publishing unrelated agent products in dd-openfeature.
  minimize {
    exclude(project(":products:feature-flagging:feature-flagging-lib"))
  }

  duplicatesStrategy = DuplicatesStrategy.FAIL
  exclude("**/META-INF/maven/**/pom.xml")
  exclude("com/squareup/moshi/_MoshiKotlin*")
  exclude("META-INF/proguard/")
  exclude("META-INF/*.kotlin_module")
}

tasks.test {
  dependsOn(tasks.named("shadowJar"))
  doFirst {
    val shadowJar =
      tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get()
    systemProperty("datadog.test.dd-openfeature.jar", shadowJar.archiveFile.get().asFile.absolutePath)
  }
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
