import com.diffplug.gradle.spotless.SpotlessExtension
import datadog.gradle.plugin.ci.testAggregate

plugins {
  id("dd-trace-java.gradle-debug")
  id("dd-trace-java.dependency-locking")
  id("dd-trace-java.tracer-version")
  id("dd-trace-java.dump-hanged-test")
  id("dd-trace-java.config-inversion-linter")
  id("dd-trace-java.ci-jobs")

  id("com.diffplug.spotless") version "6.13.0"
  id("com.github.spotbugs") version "6.4.5"
  id("de.thetaphi.forbiddenapis") version "3.10"
  id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
  id("com.gradleup.shadow") version "8.3.6" apply false
  id("me.champeau.jmh") version "0.7.3" apply false
  id("org.gradle.playframework") version "0.13" apply false
  kotlin("jvm") version libs.versions.kotlin.plugin apply false
}

description = "dd-trace-java"

val isCI = providers.environmentVariable("CI")

apply(from = rootDir.resolve("gradle/repositories.gradle"))

spotless {
  // only resolve the spotless dependencies once in the build
  predeclareDeps()
}

with(extensions["spotlessPredeclare"] as SpotlessExtension) {
  // these need to align with the types and versions in gradle/spotless.gradle
  java {
    removeUnusedImports()

    // This is the last Google Java Format version that supports Java 8
    googleJavaFormat("1.7")
  }
  groovyGradle {
    greclipse()
  }
  groovy {
    greclipse()
  }
  kotlinGradle {
    ktlint("0.41.0")
  }
  kotlin {
    ktlint("0.41.0")
  }
  scala {
    scalafmt("2.7.5")
  }
}
apply(from = rootDir.resolve("gradle/spotless.gradle"))

val compileTask = tasks.register("compile")

allprojects {
  group = "com.datadoghq"

  if (isCI.isPresent) {
    layout.buildDirectory = providers.provider {
      val newProjectCIPath = projectDir.path.replace(
        rootDir.path,
        ""
      )
      rootDir.resolve("workspace/$newProjectCIPath/build/")
    }
  }

  apply(from = rootDir.resolve("gradle/dependencies.gradle"))
  apply(from = rootDir.resolve("gradle/util.gradle"))

  compileTask.configure {
    dependsOn(tasks.withType<AbstractCompile>())
  }

  tasks.configureEach {
    if (this is JavaForkOptions) {
      maxHeapSize = System.getProperty("datadog.forkedMaxHeapSize")
      minHeapSize = System.getProperty("datadog.forkedMinHeapSize")
      jvmArgs(
        "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=/tmp"
      )
    }
  }
}

tasks.register("latestDepTest")

nexusPublishing {
  repositories {
    val forceLocal = providers.gradleProperty("forceLocal").getOrElse("false").toBoolean()
    if (forceLocal && !isCI.isPresent) {
      // For testing, use with https://hub.docker.com/r/sonatype/nexus
      // $ docker run --rm -d -p 8081:8081 --name nexus sonatype/nexus:oss
      // $ ./gradlew publishToLocal -PforceLocal=true
      // Doesn't work for testing releases though... (due to staging),
      // however, it's possible to explore http://localhost:8081/nexus/
      register("local") {
        nexusUrl = uri("http://localhost:8081/nexus/content/repositories/releases/")
        snapshotRepositoryUrl = uri("http://localhost:8081/nexus/content/repositories/snapshots/")
        username = "admin"
        password = "admin123"
        allowInsecureProtocol = true
      }
    } else {
      // see https://github.com/gradle-nexus/publish-plugin#publishing-to-maven-central-via-sonatype-central
      // For official documentation:
      // staging repo publishing https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration
      // snapshot publishing https://central.sonatype.org/publish/publish-portal-snapshots/#publishing-via-other-methods
      sonatype {
        nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
        snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
        username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME")
        password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")
      }
    }
  }
}

val writeMainVersionFileTask = tasks.register("writeMainVersionFile") {
  val versionFile = rootProject.layout.buildDirectory.file("main.version")
  inputs.property("version", project.version)
  outputs.file(versionFile)
  doFirst {
    require(versionFile.get().asFile.parentFile.mkdirs() || versionFile.get().asFile.parentFile.isDirectory)
    versionFile.get().asFile.writeText(project.version.toString())
  }
}

allprojects {
  tasks.withType<PublishToMavenLocal>().configureEach {
    finalizedBy(writeMainVersionFileTask)
  }
}

testAggregate("smoke", listOf(":dd-smoke-tests"), emptyList())
testAggregate("instrumentation", listOf(":dd-java-agent:instrumentation"), emptyList())
testAggregate("profiling", listOf(":dd-java-agent:agent-profiling"), emptyList())
testAggregate("debugger", listOf(":dd-java-agent:agent-debugger"), forceCoverage = true)
testAggregate(
  "base", listOf(":"),
  listOf(
    ":dd-java-agent:instrumentation",
    ":dd-smoke-tests",
    ":dd-java-agent:agent-profiling",
    ":dd-java-agent:agent-debugger"
  )
)
