import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val slf4jVersion = libs.findVersion("slf4j").get().requiredVersion
val slf4jSimple = "org.slf4j:slf4j-simple:$slf4jVersion"
val projectDependencies = dependencies

configurations.configureEach {
  if (name.contains("test", ignoreCase = true)) {
    if (name.endsWith("runtimeClasspath", ignoreCase = true)) {
      exclude(group = "ch.qos.logback")
    }

    if (name.endsWith("runtimeOnly", ignoreCase = true)) {
      projectDependencies.add(name, slf4jSimple)
    }
  }
}

tasks.withType<Test>().configureEach {
  jvmArgs(
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
    "-Dorg.slf4j.simpleLogger.showThreadName=true",
    "-Dorg.slf4j.simpleLogger.showDateTime=true",
    "-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS",
  )
}
