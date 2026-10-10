package datadog.gradle.plugin.overlapping

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Detects tasks with overlapping output paths and fails the build immediately.
 *
 * When Gradle runs a task, it removes stale outputs — files previously in the task's output
 * directory that are no longer part of the current execution. If two tasks share an output
 * directory, one task's stale-output cleanup silently deletes the other task's outputs.
 * The build cache makes this worse: restoring a cached task only restores that task's files,
 * leaving the shared directory in a partial state.
 *
 * This plugin registers a [org.gradle.api.execution.TaskExecutionGraph] listener that runs
 * just before task execution and fails the build if any two tasks declare the same output path.
 *
 * Applied as a settings plugin so it covers the entire build without a root-project guard.
 */
class CheckOverlappingOutputsPlugin : Plugin<Settings> {
  override fun apply(settings: Settings) {
    settings.gradle.taskGraph.whenReady {
      val outputToTasks = mutableMapOf<String, MutableList<String>>()

      allTasks.forEach { task ->
        task.outputs.files.files.forEach { output ->
          outputToTasks
            .getOrPut(output.canonicalPath) { mutableListOf() }
            .add(task.path)
        }
      }

      val overlaps = outputToTasks.filterValues { it.size > 1 }
      if (overlaps.isNotEmpty()) {
        throw GradleException(buildString {
          appendLine("Overlapping task outputs detected.")
          appendLine(
            "When a task is rerun, Gradle removes stale outputs from the shared directory, " +
              "which deletes outputs produced by other tasks sharing that path."
          )
          appendLine("Affected paths:")
          overlaps.forEach { (path, tasks) ->
            appendLine("  $path")
            tasks.forEach { appendLine("    - $it") }
          }
          append("Fix: ensure each task writes to its own unique output directory.")
        })
      }
    }
  }
}
