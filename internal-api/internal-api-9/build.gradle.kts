import groovy.lang.Closure

plugins {
  `java-library`
  id("de.thetaphi.forbiddenapis") version "3.10"
  id("dd-trace-java.jmh-conventions")
  idea
  id("dd-trace-java.module.internal-library")
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

fun AbstractCompile.configureCompiler(javaVersionInteger: Int, compatibilityVersion: JavaVersion? = null, unsetReleaseFlagReason: String? = null) {
  (project.extra["configureCompiler"] as Closure<*>).call(this, javaVersionInteger, compatibilityVersion, unsetReleaseFlagReason)
}

listOf(JavaCompile::class.java, GroovyCompile::class.java).forEach { compileTaskType ->
  tasks.withType(compileTaskType).configureEach {
    configureCompiler(11, JavaVersion.VERSION_1_8)
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
