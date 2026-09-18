plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
  id("com.gradleup.shadow")
  id("me.champeau.jmh")
}

// Tests require Java 17+ (JMC 9.1.1); on JVM 8 (where -PcheckCoverage runs) tests are skipped,
// so all main classes would have 0% coverage. Exclude them from verification.
extra["excludedClassesCoverage"] = listOf(
  "com.datadog.profiling.otel.JfrToOtlpConverter*",
  "com.datadog.profiling.otel.JfrToOtlpConverterCLI",
  "com.datadog.profiling.otel.OtlpProfileWriter",
  "com.datadog.profiling.otel.jfr.*",
  "com.datadog.profiling.otel.proto.*",
  "com.datadog.profiling.otel.proto.dictionary.*",
)

jmh {
  jmhVersion = libs.versions.jmh.get()

  // Allow filtering benchmarks via command line
  // Usage: ./gradlew jmh -PjmhIncludes="JfrToOtlpConverterBenchmark"
  // Usage: ./gradlew jmh -PjmhIncludes=".*convertJfrToOtlp"
  if (project.hasProperty("jmhIncludes")) {
    val pattern = project.property("jmhIncludes") as String
    includes = listOf(pattern)
  }

  // Profiling support
  // Usage: ./gradlew jmh -PjmhProfile=true
  // Generates flamegraph and allocation profile
  if (project.hasProperty("jmhProfile")) {
    profilers = listOf("gc", "stack")
    jvmArgs = listOf(
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:+DebugNonSafepoints"
    )
  }

  // async-profiler CPU flamegraph
  // Usage: ./gradlew jmh -PjmhAsyncProfiler=/path/to/libasyncProfiler.dylib
  // Output: /tmp/jmh-async-profile.html
  if (project.hasProperty("jmhAsyncProfiler")) {
    val lib = project.property("jmhAsyncProfiler") as String
    jvmArgs = listOf(
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:+DebugNonSafepoints",
      "-agentpath:$lib=start,event=cpu,file=/tmp/jmh-async-profile.html,flamegraph"
    )
  }
}

// OTLP validation tests removed - use profcheck validation instead (see validateOtlp task below)

repositories {
  maven {
    url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    mavenContent {
      snapshotsOnly()
    }
  }
}

configure<datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension> {
  minJavaVersion = JavaVersion.VERSION_17
}

tasks.named<JavaCompile>("compileTestJava") {
  // JMC 9.1.1 requires Java 17, and we need jdk.jfr.Event for stack trace testing
  options.release.set(17)
  javaCompiler.set(
    javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) }
  )
}

tasks.named<JavaCompile>("compileJmhJava") {
  // JMC 9.1.1 requires Java 17, and we need jdk.jfr.Event for JMH benchmarks
  options.release.set(17)
  javaCompiler.set(
    javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) }
  )
}

// Create fat jar for standalone CLI usage
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
  archiveClassifier.set("cli")
  manifest {
    attributes["Main-Class"] = "com.datadog.profiling.otel.JfrToOtlpConverterCLI"
  }
  // Exclude SLF4J service provider files to avoid warnings
  exclude("META-INF/services/org.slf4j.spi.SLF4JServiceProvider")
}

// CLI task for converting JFR files
// Usage: ./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="input.jfr output.pb"
// Usage: ./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="--json input.jfr output.json"
tasks.register<JavaExec>("convertJfr") {
  group = "application"
  description = "Convert JFR recording to OTLP profiles format"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.datadog.profiling.otel.JfrToOtlpConverterCLI")

  // Uses Gradle's built-in --args parameter which properly handles spaces in paths
}

// Build profcheck Docker image
// Usage: ./gradlew :dd-java-agent:agent-profiling:profiling-otel:buildProfcheck
tasks.register<Exec>("buildProfcheck") {
  group = "verification"
  description = "Build profcheck Docker image for OTLP validation"
  workingDir = rootDir
  commandLine("docker", "build", "-f", "docker/Dockerfile.profcheck", "-t", "profcheck:latest", ".")

  // Check if Docker is available
  doFirst {
    val process = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
    if (process.waitFor() != 0) {
      throw org.gradle.api.GradleException("Docker is not available. Profcheck validation requires Docker to be running.")
    }
  }
}

// OTLP validation tests (ProfcheckValidationTest, OtlpCollectorValidationTest) are gated behind
// the -PrunOtlpValidation Gradle property. They require Docker and network access to pull/build
// images, which is not available in the standard CI test matrix. Enable manually:
//   ./gradlew :dd-java-agent:agent-profiling:profiling-otel:test -PrunOtlpValidation
tasks.named<Test>("test") {
  if (project.hasProperty("runOtlpValidation")) {
    jvmArgs("-Drun.otlp.validation=true")

    // Build profcheck image if Docker is available (for ProfcheckValidationTest)
    doFirst {
      val dockerAvailable = try {
        val process = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
        process.waitFor() == 0
      } catch (e: Exception) {
        false
      }

      if (dockerAvailable) {
        logger.lifecycle("Building profcheck Docker image for validation tests...")
        val buildProcess = ProcessBuilder(
          "docker",
          "build",
          "-f",
          "$rootDir/docker/Dockerfile.profcheck",
          "-t",
          "profcheck:latest",
          rootDir.toString()
        ).redirectErrorStream(true).start()
        val exitCode = buildProcess.waitFor()
        if (exitCode != 0) {
          val output = buildProcess.inputStream.bufferedReader().readText()
          throw org.gradle.api.GradleException("Failed to build profcheck Docker image (exit $exitCode):\n$output")
        }
      } else {
        logger.warn("Docker not available, skipping profcheck image build. Tests tagged with 'docker' will be skipped.")
      }
    }
  }
}

// Validate OTLP output using profcheck
// Usage: ./gradlew :dd-java-agent:agent-profiling:profiling-otel:validateOtlp -PotlpFile=/path/to/output.pb
tasks.register<Exec>("validateOtlp") {
  group = "verification"
  description = "Validate OTLP profile using profcheck (requires Docker)"

  // Ensure profcheck image exists
  dependsOn("buildProfcheck")

  doFirst {
    if (!project.hasProperty("otlpFile")) {
      throw org.gradle.api.GradleException("Property 'otlpFile' is required. Usage: -PotlpFile=/path/to/output.pb")
    }

    val otlpFilePath = project.property("otlpFile") as String
    val otlpFile = file(otlpFilePath)

    if (!otlpFile.exists()) {
      throw org.gradle.api.GradleException("File not found: $otlpFilePath")
    }

    val parentDir = otlpFile.parentFile.absolutePath
    val fileName = otlpFile.name

    // Run profcheck in Docker with volume mount
    commandLine(
      "docker",
      "run",
      "--rm",
      "-v",
      "$parentDir:/data:ro",
      "profcheck:latest",
      "/data/$fileName"
    )
  }
}

dependencies {
  implementation(libs.jafar.parser)
  implementation(project(":internal-api"))
  implementation(project(":components:json"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.jmc)
  testImplementation(libs.jmc.flightrecorder.writer)
  testImplementation(libs.testcontainers)
  testImplementation("org.testcontainers:junit-jupiter:1.21.3")
  testImplementation(libs.testing.okhttp3)
}
