package datadog.buildlogic.ci

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import javax.inject.Inject

/** Tracks the declared builder identity and platform used by selected CI tasks. */
open class CiBuilderInputsExtension
  @Inject
  constructor(
    providers: ProviderFactory,
  ) {
    // CI selection changes task configuration, so it is a configuration-cache input.
    private val enabled = providers.environmentVariable("GITLAB_CI").orNull == "true"

    /** Builder identity and platform in GitLab CI; absent in local builds. */
    val identity: Provider<Map<String, String>> =
      if (enabled) {
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
      } else {
        providers.provider { null }
      }

    internal fun track(task: Task) {
      if (enabled) {
        task.inputs.property("ciBuilderIdentity", identity)
      }
    }
  }
