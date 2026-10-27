package datadog.buildlogic.ci

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskProvider
import java.util.Locale
import javax.inject.Inject

/** Tracks the image contents and platform used by selected CI tasks. */
open class CiBuilderInputsExtension
  @Inject
  constructor(
    providers: ProviderFactory,
  ) {
    // CI selection changes task configuration, so it is a configuration-cache input.
    private val enabled = providers.environmentVariable("GITLAB_CI").orNull == "true"

    private val identity =
      providers.environmentVariable("CI_BUILDER_IMAGE").orElse("").map { reference ->
        val digest =
          IMAGE_REFERENCE.matchEntire(reference)?.groupValues?.get(1)
            ?: throw GradleException(
              "CI_BUILDER_IMAGE must be an immutable image reference ending in " +
                "@sha256:<64 hexadecimal characters> when GITLAB_CI=true",
            )
        mapOf(
          "digest" to "sha256:${digest.lowercase(Locale.ROOT)}",
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

    private companion object {
      val IMAGE_REFERENCE = Regex("[^@\\s]+@sha256:([0-9a-fA-F]{64})")
    }
  }
