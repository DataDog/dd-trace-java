package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.pathSlug
import datadog.gradle.plugin.muzzle.TestedArtifact
import java.util.SortedMap

abstract class AbstractMuzzleReportTask : AbstractMuzzleTask() {
  internal fun dumpVersionsToCsv(versions: SortedMap<String, TestedArtifact>) {
    val filename = "${project.pathSlug}.csv"
    val resultsDir = project.rootProject.layout.buildDirectory
    val versionsFile = resultsDir.file("${MUZZLE_DEPS_RESULTS}/$filename")
    with(project.file(versionsFile)) {
      parentFile.mkdirs()
      writeText("instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n")
      versions.values.forEach {
        appendText(
          listOf(
            it.instrumentation,
            it.group,
            it.module,
            it.lowVersion.toString(),
            it.highVersion.toString()
          ).joinToString(",") + "\n"
        )
      }
      project.logger.info("Wrote muzzle versions report to\n  $this")
    }
  }

  companion object {
    internal const val MUZZLE_DEPS_RESULTS = "muzzle-deps-results"
  }
}
