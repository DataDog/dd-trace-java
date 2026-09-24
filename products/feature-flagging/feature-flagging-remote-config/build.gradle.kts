import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.util.Node
import groovy.util.NodeList

plugins {
  `java-library`
  id("com.gradleup.shadow")
  id("dd-trace-java.module.distributable.api")
  id("dd-trace-java.version-file")
}

description = "Optional Remote Configuration and Datadog Agent EVP transport for dd-openfeature."
base { archivesName.set("dd-openfeature-remote-config") }
java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }
configure<TestJvmConstraintsExtension> { minJavaVersion.set(JavaVersion.VERSION_11) }

dependencies {
  modules { module("com.squareup.okio:okio") { replacedBy("com.datadoghq.okio:okio") } }
  api(libs.slf4j)
  // The base customer artifact supplies this small, unshaded extension interface.
  compileOnly(project(":products:feature-flagging:feature-flagging-bootstrap"))
  implementation(project(":internal-api"))
  implementation(project(":communication"))
  implementation(project(":remote-config:remote-config-core"))
  implementation(project(":utils:version-utils"))
  testImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":utils:test-utils"))
}

publishing {
  publications.withType<MavenPublication>().configureEach {
    artifactId = "dd-openfeature-remote-config"
  }
}

// Add this after the shared publishing convention writes its API dependencies.
afterEvaluate {
  publishing.publications.withType<MavenPublication>().configureEach {
    pom.withXml {
      val nodes = asNode().get("dependencies") as NodeList
      val dependencies = nodes.firstOrNull() as Node? ?: asNode().appendNode("dependencies")
      val dependency = dependencies.appendNode("dependency")
      dependency.appendNode("groupId", "com.datadoghq")
      dependency.appendNode("artifactId", "dd-openfeature")
      dependency.appendNode("version", project.version.toString())
      dependency.appendNode("scope", "compile")
    }
  }
}

tasks.jar {
  destinationDirectory = layout.buildDirectory.dir("libs-unbundled")
  archiveClassifier = "unbundled"
}

tasks.named<ShadowJar>("shadowJar") {
  archiveClassifier = ""
  dependencies {
    exclude(dependency("org.slf4j:.*:.*"))
    exclude(dependency("com.datadoghq:java-dogstatsd-client:.*"))
    exclude(dependency("com.datadoghq:sketches-java:.*"))
    exclude(project(":products:feature-flagging:feature-flagging-bootstrap"))
  }
  // RC implementation dependencies are private to the add-on, not shared with the base JAR.
  relocate("datadog.", "com.datadog.openfeature.remoteconfig.internal.datadog.") {
    exclude("datadog.trace.api.featureflag.RemoteConfigTransport*")
  }
  relocate("com.squareup.", "com.datadog.openfeature.remoteconfig.internal.com.squareup.")
  relocate("okhttp3.", "com.datadog.openfeature.remoteconfig.internal.okhttp3.")
  relocate("okio.", "com.datadog.openfeature.remoteconfig.internal.okio.")
  relocate("org.jctools.", "com.datadog.openfeature.remoteconfig.internal.org.jctools.")
  relocate("cafe.cryptography.", "com.datadog.openfeature.remoteconfig.internal.cafe.cryptography.")
  relocate("org.snakeyaml.", "com.datadog.openfeature.remoteconfig.internal.org.snakeyaml.")
  minimize()
  duplicatesStrategy = DuplicatesStrategy.FAIL
  exclude("**/META-INF/maven/**/pom.xml")
  exclude("com/squareup/moshi/_MoshiKotlin*")
  exclude("META-INF/proguard/")
  exclude("META-INF/*.kotlin_module")
}

tasks.named("compareToReferenceJar") { enabled = false }
