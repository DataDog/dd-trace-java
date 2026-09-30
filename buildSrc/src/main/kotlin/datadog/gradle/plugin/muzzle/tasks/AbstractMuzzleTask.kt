package datadog.gradle.plugin.muzzle.tasks

import org.gradle.api.DefaultTask

abstract class AbstractMuzzleTask : DefaultTask() {
  init {
    group = "Muzzle"
    notCompatibleWithConfigurationCache("Muzzle version selection depends on publication age and must be refreshed each build")
  }
}
