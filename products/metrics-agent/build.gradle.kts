import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.named

plugins {
  `java-library`
  id("com.gradleup.shadow")
}

description = "Metrics agent"

apply(from = rootDir.resolve("gradle/java.gradle"))

dependencies {
  api(project(":products:metrics-api"))
}

tasks.named<ShadowJar>("shadowJar") {
  dependencies {
    val deps = project.extra["deps"] as Map<*, *>
    val excludeShared = deps["excludeShared"] as groovy.lang.Closure<*>
    excludeShared.delegate = this
    excludeShared.call()
  }
}
