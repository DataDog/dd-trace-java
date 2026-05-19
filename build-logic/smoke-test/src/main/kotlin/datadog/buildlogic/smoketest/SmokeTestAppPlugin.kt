package datadog.buildlogic.smoketest

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

/**
 * Exposes the [NestedGradleBuild] task type plus a `smokeTestApp` extension that wires the
 * nested-build task and Test-side system properties for a smoke-test application.
 *
 * Consumers can either:
 * - configure `smokeTestApp { application { ... } }` to let the plugin register the task and
 *   wire it into every `Test` task, or
 * - leave the extension untouched and register a [NestedGradleBuild] task manually (for cases
 *   that need more control, e.g. additional `Exec`-like task wiring).
 */
class SmokeTestAppPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.extensions.create<SmokeTestAppExtension>("smokeTestApp")
  }
}
