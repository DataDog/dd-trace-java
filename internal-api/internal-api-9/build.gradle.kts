import groovy.lang.Closure
import java.nio.file.Paths

plugins {
  `java-library`
  id("de.thetaphi.forbiddenapis") version "3.10"
  id("me.champeau.jmh")
  idea
}

apply(from = "$rootDir/gradle/java.gradle")

extensions.getByName("tracerJava").withGroovyBuilder {
  invokeMethod("addSourceSetFor", JavaVersion.VERSION_17)
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

val minimumBranchCoverage by extra(0.8)
val minimumInstructionCoverage by extra(0.8)

dependencies {
  api(project(":internal-api"))

  testImplementation(project(":dd-java-agent:testing"))
  testImplementation(libs.slf4j)
}

tasks.forbiddenApisMain {
  failOnMissingClasses = false
}

idea {
  module {
    jdkName = "11"
  }
}

jmh {
  jmhVersion = libs.versions.jmh
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
  jvm = providers.environmentVariable("JAVA_11_HOME").map { Paths.get(it, "bin", "java").toString() }
}
