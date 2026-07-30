import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import groovy.lang.Closure
import org.gradle.api.tasks.SourceSetContainer
import java.util.zip.ZipFile

plugins {
  `java-library`
  idea
  `maven-publish`
}

apply(from = "$rootDir/gradle/java.gradle")
apply(from = "$rootDir/gradle/publish.gradle")

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
  implementation(libs.moshi)
  implementation(libs.slf4j)

  compileOnly(project(":products:feature-flagging:feature-flagging-bootstrap"))
  compileOnly(project(":products:feature-flagging:feature-flagging-config"))
  compileOnly(project(":products:feature-flagging:feature-flagging-core"))
  compileOnly(project(":products:feature-flagging:feature-flagging-http"))
  compileOnly(project(":utils:config-utils"))
  compileOnly("io.opentelemetry:opentelemetry-api:1.47.0")

  testImplementation(project(":products:feature-flagging:feature-flagging-bootstrap"))
  testImplementation(project(":products:feature-flagging:feature-flagging-core"))
  testImplementation(project(":products:feature-flagging:feature-flagging-http"))
  testImplementation(project(":utils:config-utils"))
  testImplementation("io.opentelemetry:opentelemetry-api:1.47.0")
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.moshi)
  testImplementation("org.awaitility:awaitility:4.3.0")
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

val coreProject = project(":products:feature-flagging:feature-flagging-core")
val httpProject = project(":products:feature-flagging:feature-flagging-http")
val bootstrapProject = project(":products:feature-flagging:feature-flagging-bootstrap")

tasks.named<Jar>("jar") {
  from(coreProject.extensions.getByType<SourceSetContainer>().named("main").map { it.output })
  from(httpProject.extensions.getByType<SourceSetContainer>().named("main").map { it.output })
  from(bootstrapProject.extensions.getByType<SourceSetContainer>().named("main").map { it.output }) {
    include("datadog/trace/api/featureflag/ufc/v1/**")
  }
}

tasks.withType<Test>().configureEach {
  dependsOn(tasks.named("jar"))
  systemProperty(
    "dd.openfeature.test.jar",
    tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath
  )
}

// The dd-openfeature provider jar is not produced by the CI `build` job, so there is no reference
// artifact to compare against. Disable the release jar comparison gate registered by publish.gradle.
tasks.named("compareToReferenceJar") {
  enabled = false
}

tasks.register("verifyDdOpenfeatureArtifact") {
  dependsOn(tasks.named("jar"), tasks.named("generatePomFileForMavenPublication"))
  doLast {
    val providerJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
    val requiredEntries = setOf(
      "datadog/openfeature/internal/core/ConfigurationStore.class",
      "datadog/openfeature/internal/core/FlagEvaluator.class",
      "datadog/openfeature/internal/http/CdnConfigurationSource.class",
      "datadog/openfeature/internal/http/HttpConfigurationOptions.class",
      "datadog/trace/api/featureflag/ufc/v1/ServerConfiguration.class"
    )
    ZipFile(providerJar).use { zip ->
      val entryNames = zip.entries().asSequence().map { it.name }.toSet()
      val missing = requiredEntries - entryNames
      check(missing.isEmpty()) {
        "dd-openfeature is missing embedded standalone classes: $missing"
      }
    }

    val pom = layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile
    check(pom.isFile) { "Generated dd-openfeature Maven POM is missing" }
    val pomText = pom.readText()
    check(pomText.contains("<artifactId>sdk</artifactId>"))
    check(pomText.contains("<artifactId>moshi</artifactId>"))
    check(!pomText.contains("feature-flagging-core"))
    check(!pomText.contains("feature-flagging-http"))
  }
}
