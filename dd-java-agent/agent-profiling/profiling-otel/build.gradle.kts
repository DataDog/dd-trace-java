plugins {
  `java-library`
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

// OTel Collector validation tests (requires Docker)
tasks.register<Test>("validateOtlp") {
  group = "verification"
  description = "Validates OTLP profiles against real OpenTelemetry Collector (requires Docker)"

  // Only run the collector validation tests
  useJUnitPlatform {
    includeTags("otlp-validation")
  }

  // Ensure test classes are compiled
  dependsOn(tasks.named("testClasses"))

  // Use the test runtime classpath
  classpath = sourceSets["test"].runtimeClasspath
  testClassesDirs = sourceSets["test"].output.classesDirs
}

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
