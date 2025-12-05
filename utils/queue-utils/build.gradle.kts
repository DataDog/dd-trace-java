import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import groovy.lang.Closure
import org.gradle.kotlin.dsl.extra

plugins {
  `java-library`
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
/*
tasks.named<CheckForbiddenApis>("forbiddenApisMain_java11") {
  failOnMissingClasses = false
}
*/
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

dependencies {
  api(project(":internal-api"))
  api(libs.jctools)

  testImplementation(project(":dd-java-agent:testing"))
  testImplementation(libs.slf4j)
}

idea {
  module {
    jdkName = "11"
  }
}
