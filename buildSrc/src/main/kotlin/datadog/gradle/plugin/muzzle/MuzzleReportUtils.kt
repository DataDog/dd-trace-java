package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.highest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.lowest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.resolveInstrumentationAndJarVersions
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType
import java.io.File
import java.net.URLClassLoader
import java.util.SortedMap
import java.util.TreeMap
import java.util.function.BiFunction

object MuzzleReportUtils {
  @JvmStatic
  fun dumpVersionRanges(project: Project) {
    val system: RepositorySystem = MuzzleMavenRepoUtils.newRepositorySystem()
    val session: RepositorySystemSession = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    val versions = TreeMap<String, TestedArtifact>()
    val directives = project.extensions.findByType<MuzzleExtension>()?.directives ?: emptyList()
    directives.filter { !it.coreJdk && !it.skipFromReport }.forEach { directive ->
      val range = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, session)
      val cp = project.files(project.mainSourceSet.runtimeClasspath).map { it.toURI().toURL() }.toTypedArray()
      val cl = URLClassLoader(cp, null)
      val partials = resolveInstrumentationAndJarVersions(directive, cl, range.lowestVersion, range.highestVersion)
      partials.forEach { (key, value) ->
        versions.merge(key, value, BiFunction { x, y ->
          TestedArtifact(
            x.instrumentation, x.group, x.module,
            lowest(x.lowVersion, y.lowVersion),
            highest(x.highVersion, y.highVersion)
          )
        })
      }
    }
    dumpVersionsToCsv(project, versions)
  }

  private fun dumpVersionsToCsv(project: Project, versions: SortedMap<String, TestedArtifact>) {
    val filename = project.path.replaceFirst("^:", "").replace(":", "_")
    val dir = project.rootProject.layout.buildDirectory.dir("muzzle-deps-results").get().asFile.apply {
      mkdirs()
    }
    with(File(dir, "$filename.csv")) {
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

    dumpVersionsToCsv(project, map)
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
