import datadog.gradle.configureCompiler

plugins {
  `java-library`
  idea
  id("dd-trace-java.module.internal-library")
}

extensions.getByName("tracerJava").withGroovyBuilder {
  invokeMethod("addSourceSetFor", arrayOf(JavaVersion.VERSION_17, mapOf("compileOnly" to true)))
}

dependencies {
  implementation(project(":components:environment"))
  implementation(project(":utils:logging-utils"))
  implementation(libs.slf4j)
  implementation(libs.jnr.unixsocket)
  testImplementation(files(sourceSets["main_java17"].output))
}

listOf("compileMain_java17Java", "compileTestJava").forEach {
  tasks.named<JavaCompile>(it) {
    // The Java 17 implementation can lift this offset, but compileTestJava must first be split if
    // the remaining socket tests still need to run on Java 8.
    configureCompiler(25, JavaVersion.VERSION_1_8, "Uses java.net.UnixDomainSocketAddress (Java 16+) at Java 8 bytecode")
  }
}

idea {
  module {
    jdkName = "17"
  }
}
