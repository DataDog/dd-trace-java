import groovy.lang.Closure

plugins {
  `java-library`
  id("de.thetaphi.forbiddenapis") version "3.8"
  id("me.champeau.jmh")
  idea
}

val minJavaVersionForTests by extra(JavaVersion.VERSION_11)

apply(from = "$rootDir/gradle/java.gradle")

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

tasks.withType<Javadoc> {
  javadocTool = javaToolchains.javadocToolFor(java.toolchain)
}

fun AbstractCompile.setJavaVersion(javaVersionInteger: Int) {
  (project.extra.get("setJavaVersion") as Closure<*>).call(this, javaVersionInteger)
}

listOf(JavaCompile::class.java, GroovyCompile::class.java).forEach { compileTaskType ->
  tasks.withType(compileTaskType) {
    setJavaVersion(11)
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
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
  jmhVersion = "1.28"
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
  jvm = System.getenv("JAVA_11_HOME") + "/bin/java"
}
