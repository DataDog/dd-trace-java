import datadog.gradle.configureCompiler

plugins {
  `java-library`
  id("de.thetaphi.forbiddenapis") version "3.10"
  id("dd-trace-java.jmh-conventions")
  idea
  id("dd-trace-java.module.internal-api")
}

extensions.getByName("tracerJava").withGroovyBuilder {
  invokeMethod("addSourceSetFor", JavaVersion.VERSION_17)
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

listOf(JavaCompile::class.java, GroovyCompile::class.java).forEach { compileTaskType ->
  tasks.withType(compileTaskType).configureEach {
    // These implementations are selected only on Java 9+, so they can target Java 9 and restore
    // --release after confirming no project output must be loaded during Java 8 discovery.
    configureCompiler(25, JavaVersion.VERSION_1_8, "Uses Java 9+ APIs (StackWalker, ProcessHandle, Module) at Java 8 bytecode")
  }
}

extra["minimumBranchCoverage"] = 0.8
extra["minimumInstructionCoverage"] = 0.8

dependencies {
  api(project(":internal-api"))

  testImplementation(project(":dd-java-agent:testing"))
  testImplementation(libs.slf4j)
}

idea {
  module {
    jdkName = "11"
  }
}

jmh {
  jmhVersion = libs.versions.jmh
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}
