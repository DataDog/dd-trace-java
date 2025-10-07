package datadog.gradle.plugin.muzzle.tasks

import org.gradle.api.DefaultTask

abstract class AbstractMuzzleTask : DefaultTask() {
  init {
    group = "Muzzle"
  }
}
