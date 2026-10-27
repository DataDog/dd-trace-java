package datadog.buildlogic.ci

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject

/** Tracks the declared builder identity and platform used by selected CI tasks. */
open class CiBuilderInputsExtension
  @Inject
  constructor(
    providers: ProviderFactory,
  ) {
    // CI selection changes task configuration, so it is a configuration-cache input.
    private val enabled = providers.environmentVariable("GITLAB_CI").orNull == "true"

    private val identity =
      providers.gradleProperty("ciBuilderImage").orElse("").map { image ->
        if (image.isBlank()) {
          throw GradleException("ciBuilderImage must be a nonblank CI builder identity when GITLAB_CI=true")
        }
        mapOf(
          "image" to image,
          "operatingSystem" to System.getProperty("os.name"),
          "architecture" to System.getProperty("os.arch"),
        )
      }

    /** Adds the builder identity to a task that consumes tools or libraries from the image. */
    fun track(task: TaskProvider<out Task>) {
      if (enabled) {
        task.configure { track(this) }
      }
    }

    internal fun track(task: Task) {
      if (enabled) {
        task.inputs.property("ciBuilderIdentity", identity)
      }
    }
  }
