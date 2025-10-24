import groovy.lang.Closure

plugins {
  `java-library`
  idea
}

apply(from = "$rootDir/gradle/java.gradle")

val minJavaVersionForTests by extra(JavaVersion.VERSION_11)

description = "open-feature"

idea {
  module {
    jdkName = "11"
  }
}

dependencies {
  api(libs.slf4j)
  api(libs.openfeature.sdk)
  api(project(":dd-trace-api"))
}

fun AbstractCompile.configureCompiler(javaVersionInteger: Int, compatibilityVersion: JavaVersion? = null, unsetReleaseFlagReason: String? = null) {
  (project.extra["configureCompiler"] as Closure<*>).call(this, javaVersionInteger, compatibilityVersion, unsetReleaseFlagReason)
}

listOf(JavaCompile::class.java, GroovyCompile::class.java).forEach { compileTaskType ->
  tasks.withType(compileTaskType).configureEach {
    configureCompiler(11, JavaVersion.VERSION_11)
  }
}

tasks.withType<Javadoc>().configureEach {
  // TODO ensure it uses JDK 11
  enabled = false
}
