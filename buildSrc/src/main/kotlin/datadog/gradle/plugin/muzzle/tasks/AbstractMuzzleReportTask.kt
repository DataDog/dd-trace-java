package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.TestedArtifact
import datadog.gradle.plugin.muzzle.pathSlug
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.OutputFile
import java.util.SortedMap

abstract class AbstractMuzzleReportTask : AbstractMuzzleTask() {
  @get:OutputFile
  val versionsFile: Provider<RegularFile> =
    project.rootProject
      .layout
      .buildDirectory
      .file("$MUZZLE_DEPS_RESULTS/${project.pathSlug}.csv")

  internal fun dumpVersionsToCsv(versions: SortedMap<String, TestedArtifact>) {
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
