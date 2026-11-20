import datadog.gradle.configureCompiler

plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
  id("dd-trace-java.jmh-conventions")
}

extensions.getByName("tracerJava").withGroovyBuilder {
  invokeMethod("addSourceSetFor", arrayOf(JavaVersion.VERSION_17, mapOf("compileOnly" to true)))
}

extra["minimumBranchCoverage"] = 0.8
extra["minimumInstructionCoverage"] = 0.8
// PatchInit only runs in the agent jar, which relocates jffi's Init class to it (dd-java-agent/build.gradle)
extra["excludedClassesCoverage"] = listOf("com.kenai.jffi.PatchInit")

dependencies {
  compileOnly(project(":components:annotations"))
  add("main_java17CompileOnly", project(":components:annotations"))
  implementation(project(":components:environment"))
  implementation(project(":utils:logging-utils"))
  implementation(libs.slf4j)
  implementation(libs.jnr.unixsocket)
  implementation(libs.bundles.asm) // JNR's ASM dependencies are supplied at our controlled version.
  testImplementation(files(sourceSets["main_java17"].output))
  testImplementation(libs.bundles.mockito)
  jmhImplementation(files(sourceSets["main_java17"].output))
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  includeTests = false
  resultFormat = "JSON"
  failOnError = true
}

listOf("compileMain_java17Java", "compileTestJava", "compileJmhJava").forEach {
  tasks.named<JavaCompile>(it) {
    // The Java 17 implementation can lift this offset, but compileTestJava must first be split if
    // the remaining socket tests still need to run on Java 8.
    configureCompiler(JavaVersion.VERSION_1_8, "Uses java.net.UnixDomainSocketAddress (Java 16+) at Java 8 bytecode")
  }
}
