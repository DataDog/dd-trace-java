package datadog.gradle.plugin.muzzle

import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.Project
import java.io.File
import java.util.*
import java.util.function.BiFunction

object MuzzleReportUtils {
  /**
   * Merges all muzzle report CSVs in the build directory into a single map and writes the merged results to a CSV.
   */
  @JvmStatic
  fun mergeReports(project: Project) {
    val dir = File(project.rootProject.buildDir, "muzzle-deps-results")
    val map = TreeMap<String, TestedArtifact>()
    val versionScheme = GenericVersionScheme()
    dir.listFiles { file -> file.name.endsWith(".csv") }?.forEach { file ->
      file.useLines { lines ->
        lines.forEachIndexed { idx, line ->
          if (idx == 0) return@forEachIndexed // skip header
          val split = line.split(",")
          val parsed = TestedArtifact(
            split[0],
            split[1],
            split[2],
            versionScheme.parseVersion(split[3]),
            versionScheme.parseVersion(split[4])
          )
          map.merge(parsed.key(), parsed) { x, y ->
            TestedArtifact(
              x.instrumentation,
              x.group,
              x.module,
              MuzzleMavenRepoUtils.lowest(x.lowVersion, y.lowVersion),
              MuzzleMavenRepoUtils.highest(x.highVersion, y.highVersion)
            )
          }
        }
      }
    }
    MuzzleMavenRepoUtils.dumpVersionsToCsv(project, map)
  }

  /**
   * Generates a JUnit-style XML report for muzzle results.
   */
  @JvmStatic
  fun generateResultsXML(project: Project, millis: Long) {
    val seconds = millis.toDouble() / 1000.0
    val name = "${project.path}:muzzle"
    val dirname = name.replaceFirst("^:", "").replace(":", "_")
    val dir = File(project.rootProject.buildDir, "muzzle-test-results/$dirname")
    dir.mkdirs()
    val file = File(dir, "results.xml")
    file.writeText(
      """
        <?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<testsuite name=\"$name\" tests=\"1\" id=\"0\" time=\"$seconds\">\n" +
        "  <testcase name=\"$name\" time=\"$seconds\">\n" +
        "  </testcase>\n" +
        "</testsuite>\n"
        """.trimIndent()
    )
  }
}

