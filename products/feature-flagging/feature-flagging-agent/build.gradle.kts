import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.project

plugins {
  `java-library`
  id("com.gradleup.shadow")
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.agent-product")
}

description = "Feature flagging agent system"

dependencies {
  api(libs.slf4j)
  api(project(":products:feature-flagging:feature-flagging-lib"))
  api(project(":internal-api"))

  testImplementation(project(":utils:test-utils"))
  testRuntimeOnly(project(":dd-trace-core"))
}

tasks.named<ShadowJar>("shadowJar") {
  dependencies {
    val deps = project.extra["deps"] as Map<*, *>
    val excludeShared = deps["excludeShared"] as groovy.lang.Closure<*>
    excludeShared.delegate = this
    excludeShared.call()
  }
}
