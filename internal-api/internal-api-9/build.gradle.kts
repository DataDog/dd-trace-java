import datadog.gradle.configureCompiler

plugins {
  `java-library`
  id("dd-trace-java.jmh-conventions")
  id("dd-trace-java.module.internal-api")
}

testJvmConstraints {
  minJavaVersion = JavaVersion.VERSION_11
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

tasks.withType<Javadoc>().configureEach {
  javadocTool = javaToolchains.javadocToolFor(java.toolchain)
}

tasks.withType<JavaCompile>().configureEach {
  // These implementations are selected only on Java 9+, so they can target Java 9 and restore
  // --release after confirming no project output must be loaded during Java 8 discovery.
  configureCompiler(JavaVersion.VERSION_1_8, "Uses Java 9+ APIs (StackWalker, ProcessHandle, Module) at Java 8 bytecode")
}

extra["minimumBranchCoverage"] = 0.8
extra["minimumInstructionCoverage"] = 0.8
// Remaining uncovered code is JVM-specific fallbacks (ProcessHandle failures, IBM J9 frame mapping)
// that cannot be reached on the test JVMs.
extra["excludedClassesCoverage"] = listOf(
  "datadog.trace.util.JDK9ExecutableSupplier",
  "datadog.trace.util.JDK9PidSupplier",
  "datadog.trace.util.stacktrace.JDK9StackWalker",
)

dependencies {
  api(project(":internal-api"))

  testImplementation(project(":dd-java-agent:testing"))
  testImplementation(libs.slf4j)
}

jmh {
  jmhVersion = libs.versions.jmh
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}
