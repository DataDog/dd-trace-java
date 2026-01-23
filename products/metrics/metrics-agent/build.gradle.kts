import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.named

plugins {
  `java-library`
  id("com.gradleup.shadow")
}

description = "Metrics agent"

apply(from = rootDir.resolve("gradle/java.gradle"))

dependencies {
  api(project(":products:metrics:metrics-api"))
}

// Configuration to include metrics-lib in shadowJar (but not as transitive dependency)
val shadowInclude by configurations.registering {
  isCanBeResolved = true
  isCanBeConsumed = false

  dependencies.add(project.dependencies.project(":products:metrics:metrics-lib"))
}

tasks.named<ShadowJar>("shadowJar") {
  configurations = listOf(
    project.configurations.runtimeClasspath.get(),
    shadowInclude.get()
  )

  // 'excludeShared' excludes metrics-lib which we want to be bundled to land in metrics/
  dependencies {
    // Exclude shared/bootstrap projects
    exclude(project(":dd-java-agent:agent-bootstrap"))
    exclude(project(":dd-java-agent:agent-logging"))
    exclude(project(":dd-trace-api"))
    exclude(project(":internal-api"))
    exclude(project(":components:environment"))
    exclude(project(":components:json"))
    // Exclude metrics-api (keep it on bootstrap)
    exclude(project(":products:metrics:metrics-api"))

    exclude(dependency("org.slf4j::"))

    // dogstatsd and its transitives
    exclude(dependency("com.datadoghq:java-dogstatsd-client"))
    exclude(dependency("com.github.jnr::"))
    exclude(dependency("org.ow2.asm::"))

    // sketches-java is in shared
    exclude(dependency("com.datadoghq:sketches-java"))
  }
}
