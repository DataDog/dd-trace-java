import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.lang.Closure

plugins {
  `java-library`
  idea
  id("com.gradleup.shadow")
  id("dd-trace-java.module.distributable.api")
  id("dd-trace-java.version-file")
}

configure<TestJvmConstraintsExtension> {
  minJavaVersion.set(JavaVersion.VERSION_11)
}

description = "Standalone Datadog OpenFeature provider; Remote Configuration is an optional extension."

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
  api("io.opentelemetry:opentelemetry-api:1.57.0")
  // This assembly supplies the full shared library and application APIs explicitly. Do not also
  // include the adapter's evaluator-only artifact, which would duplicate the evaluator classes.
  implementation(project(":products:feature-flagging:feature-flagging-api")) { isTransitive = false }

  implementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  implementation(project(":products:feature-flagging:feature-flagging-config"))
  implementation(project(":products:feature-flagging:feature-flagging-lib"))
  implementation(project(":utils:config-utils"))
  implementation(project(":internal-api"))
  implementation(project(":communication"))
  // OpenFeature SDK classes retain @lombok.Generated in their bytecode. Supplying the annotation
  // on the analysis classpath keeps SpotBugs from treating that optional SDK build detail as a
  // missing class; Lombok is neither bundled nor published as a dependency.
  compileOnly("org.projectlombok:lombok:1.18.38")

  testImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  testImplementation(project(":utils:config-utils"))
  testImplementation("io.opentelemetry:opentelemetry-api:1.57.0")
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.moshi)

  testImplementation(project(":utils:test-utils"))
}

tasks.jar {
  destinationDirectory = layout.buildDirectory.dir("libs-unbundled")
  archiveClassifier = "unbundled"
}

// Publish the sources behind the customer artifact, not only its composition root.
tasks.named<Jar>("sourcesJar") {
  from(project(":products:feature-flagging:feature-flagging-api").file("src/main/java"))
  from(project(":products:feature-flagging:feature-flagging-lib").file("src/main/java"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
  archiveClassifier = ""

  dependencies {
    exclude(dependency("dev.openfeature:sdk:.*"))
    exclude(dependency("io.opentelemetry:.*:.*"))
    exclude(dependency("org.slf4j:.*:.*"))
    // RC and Agent connection dependencies belong only to the opt-in RC extension.
    exclude(project(":remote-config:remote-config-api"))
    exclude(project(":remote-config:remote-config-core"))
    exclude(dependency("cafe.cryptography:.*:.*"))
    exclude(dependency("com.github.jnr:.*:.*"))
    exclude(dependency("org.ow2.asm:.*:.*"))
    exclude(dependency("com.datadoghq:java-dogstatsd-client:.*"))
    exclude(dependency("com.datadoghq:sketches-java:.*"))
  }

  relocate("com.datadog.featureflag.", "datadog.openfeature.internal.featureflag.")
  relocate("com.squareup.", "datadog.openfeature.internal.com.squareup.")
  relocate("okhttp3.", "datadog.openfeature.internal.okhttp3.")
  relocate("okio.", "datadog.openfeature.internal.okio.")
  relocate("org.jctools.", "datadog.openfeature.internal.org.jctools.")
  relocate("datadog.", "datadog.openfeature.internal.datadog.") {
    exclude("datadog.trace.api.featureflag.**")
    exclude("datadog.trace.api.openfeature.*")
  }

  // These JARs are minimization entrypoints, not Maven API dependencies. Follow references from
  // their classes, including reflectively loaded provider/evaluator classes, without retaining
  // the whole communication/RC graph as minimize { exclude(project(...)) } would.
  apiJars.from(
    project(":products:feature-flagging:feature-flagging-api").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    project(":products:feature-flagging:feature-flagging-lib").tasks.named<Jar>("jar").flatMap { it.archiveFile }
  )
  minimize()

  duplicatesStrategy = DuplicatesStrategy.FAIL
  exclude("**/META-INF/maven/**/pom.xml")
  exclude("com/squareup/moshi/_MoshiKotlin*")
  exclude("META-INF/proguard/")
  exclude("META-INF/*.kotlin_module")
}

tasks.test {
  dependsOn(tasks.named("shadowJar"))
  dependsOn(":products:feature-flagging:feature-flagging-remote-config:shadowJar")
  doFirst {
    val shadowJar =
      tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get()
    systemProperty("datadog.test.dd-openfeature.jar", shadowJar.archiveFile.get().asFile.absolutePath)
    systemProperty("datadog.test.provider.version", project.version.toString().replace('~', '+'))
    val rcJar = project(":products:feature-flagging:feature-flagging-remote-config")
      .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get()
    systemProperty("datadog.test.dd-openfeature-remote-config.jar", rcJar.archiveFile.get().asFile.absolutePath)
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
