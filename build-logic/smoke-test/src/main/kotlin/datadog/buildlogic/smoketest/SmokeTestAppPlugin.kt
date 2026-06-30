package datadog.buildlogic.smoketest

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.withType

/**
 * Exposes nested build task types plus a `smokeTestApp` extension that wires a smoke-test
 * application build and Test-side system properties.
 *
 * Consumers can either:
 * - configure `smokeTestApp { gradleApp { ... } }` or `smokeTestApp { mavenApp { ... } }` to
 *   let the plugin register the task and wire it into every `Test` task, or
 * - leave the extension untouched and register a task manually for cases that need more control.
 */
class SmokeTestAppPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create<SmokeTestAppExtension>("smokeTestApp")
    project.tasks.withType<NestedGradleBuild>().configureEach {
      initScripts.convention(extension.initScripts)
      gradleProperties.convention(extension.gradleProperties)
    }
  }
}
