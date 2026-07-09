import com.github.jengelman.gradle.plugins.shadow.tasks.DependencyFilter
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Action
import org.gradle.kotlin.dsl.project

plugins {
  `java-library`
  id("com.gradleup.shadow")
  id("dd-trace-java.version-file")
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Feature flagging agent system"

dependencies {
  api(libs.slf4j)
  api(project(":products:feature-flagging:feature-flagging-lib"))
  api(project(":internal-api"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":utils:test-utils"))
  testRuntimeOnly(project(":dd-trace-core"))
}

tasks.named<ShadowJar>("shadowJar") {
  dependencies {
    val deps = project.extra["deps"] as Map<*, *>
    val excludeShared = deps["excludeShared"] as Action<DependencyFilter>
    excludeShared.execute(this)
  }
}
