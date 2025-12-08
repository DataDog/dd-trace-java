import groovy.lang.Closure

plugins {
  `java-library`
  id("me.champeau.jmh")
  id("dd-trace-java.test-jvm-contraints")
  idea
}

apply(from = "$rootDir/gradle/java.gradle")

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

fun AbstractCompile.configureCompiler(
  javaVersionInteger: Int,
  compatibilityVersion: JavaVersion? = null,
  unsetReleaseFlagReason: String? = null
) {
  (project.extra["configureCompiler"] as Closure<*>).call(
    this,
    javaVersionInteger,
    compatibilityVersion,
    unsetReleaseFlagReason
  )
}

listOf(JavaCompile::class.java, GroovyCompile::class.java).forEach { compileTaskType ->
  tasks.withType(compileTaskType).configureEach {
    configureCompiler(11, JavaVersion.VERSION_1_8)
  }
}

dependencies {
  api(project(":internal-api"))
  api(libs.jctools)

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.slf4j)
}

testJvmConstraints {
  minJavaVersion = JavaVersion.VERSION_11
}

idea {
  module {
    jdkName = "11"
  }
}
