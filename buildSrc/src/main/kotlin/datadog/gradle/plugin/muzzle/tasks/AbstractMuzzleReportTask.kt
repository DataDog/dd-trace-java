package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.TestedArtifact
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import java.util.SortedMap

abstract class AbstractMuzzleReportTask : AbstractMuzzleTask() {
  @get:OutputFile
  abstract val versionsFile: RegularFileProperty

  internal fun dumpVersionsToCsv(versions: SortedMap<String, TestedArtifact>) {
    val file = versionsFile.get().asFile
    file.parentFile.mkdirs()

    file.bufferedWriter().use { writer ->
      writer.append("instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n")
      versions.values.forEach {
        writer.append(
          listOf(
            it.instrumentation,
            it.group,
            it.module,
            it.lowVersion.toString(),
            it.highVersion.toString()
          ).joinToString(",")
        ).append('\n')
      }
    }

    logger.info("Wrote muzzle versions report to\n  $file")
  }

  companion object {
    internal const val MUZZLE_DEPS_RESULTS = "muzzle-deps-results"
  }
}
