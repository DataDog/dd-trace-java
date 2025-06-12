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
    languageVersion.set(JavaLanguageVersion.of(11))
  }
}

tasks.withType<Javadoc> {
  javadocTool.set(javaToolchains.javadocToolFor(java.toolchain))
}

tasks.withType<JavaCompile> {
  sourceCompatibility = JavaVersion.VERSION_1_8.toString()
  targetCompatibility = JavaVersion.VERSION_1_8.toString()
  // setJavaVersion(this, 11) // This needs to be adapted or might be covered by toolchain
}
tasks.withType<GroovyCompile> {
  sourceCompatibility = JavaVersion.VERSION_1_8.toString()
  targetCompatibility = JavaVersion.VERSION_1_8.toString()
  // setJavaVersion(this, 11) // This needs to be adapted or might be covered by toolchain
}


val minimumBranchCoverage by extra(0.8)
val minimumInstructionCoverage by extra(0.8)

dependencies {
  api(project(":internal-api"))

  testImplementation(project(":dd-java-agent:testing"))
  testImplementation(libs.slf4j)
}

tasks.forbiddenApisMain.configure {
    failOnMissingClasses = false
}

idea {
  module {
    jdkName = "11"
  }
}

jmh {
  jmhVersion.set("1.28")
  duplicateClassesStrategy.set(DuplicatesStrategy.EXCLUDE)
  jvm.set(System.getenv("JAVA_11_HOME") + "/bin/java")
}
