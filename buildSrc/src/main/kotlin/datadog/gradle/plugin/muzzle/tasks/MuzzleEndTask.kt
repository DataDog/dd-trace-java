package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.pathSlug
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class MuzzleEndTask : AbstractMuzzleTask() {
  @get:Input
  abstract val startTimeMs: Property<Long>

  @get:OutputFile
  val resultsFile = project.rootProject
    .layout
    .buildDirectory
    .file("${MUZZLE_TEST_RESULTS}/${project.pathSlug}_muzzle/results.xml")

  @TaskAction
  fun generatesResultFile() {
    val endTimeMs = System.currentTimeMillis()
    val seconds = (endTimeMs - startTimeMs.get()).toDouble() / 1000.0
    with(project.file(resultsFile)) {
      parentFile.mkdirs()
      writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="$name" tests="1" id="0" time="$seconds">
          <testcase name="$name" time="$seconds"/>
        </testsuite>
        """.trimIndent()
      )
      project.logger.info("Wrote muzzle results report to\n  $this")
    }
  }

  companion object {
    private const val MUZZLE_TEST_RESULTS = "muzzle-test-results"
  }
}
