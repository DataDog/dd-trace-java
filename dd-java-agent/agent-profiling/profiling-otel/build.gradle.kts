plugins {
  `java-library`
  id("com.gradleup.shadow")
  id("me.champeau.jmh")
}

apply(from = "$rootDir/gradle/java.gradle")

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
  // Minimize the jar by only including classes that are actually used
  minimize()

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
    try {
      project.exec {
        commandLine("docker", "info")
        isIgnoreExitValue = false
      }
    } catch (e: Exception) {
      throw org.gradle.api.GradleException("Docker is not available. Profcheck validation requires Docker to be running.")
    }
  }
}

// Ensure profcheck image is built before running tests with @Tag("docker")
tasks.named<Test>("test") {
  // Build profcheck image if Docker is available (for ProfcheckValidationTest)
  doFirst {
    val dockerAvailable = try {
      project.exec {
        commandLine("docker", "info")
        isIgnoreExitValue = false
      }
      true
    } catch (e: Exception) {
      false
    }

    if (dockerAvailable) {
      logger.lifecycle("Building profcheck Docker image for validation tests...")
      project.exec {
        commandLine("docker", "build", "-f", "$rootDir/docker/Dockerfile.profcheck", "-t", "profcheck:latest", rootDir.toString())
      }
    } else {
      logger.warn("Docker not available, skipping profcheck image build. Tests tagged with 'docker' will be skipped.")
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
      "docker", "run", "--rm",
      "-v", "$parentDir:/data:ro",
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
  testImplementation(libs.okhttp)
}
