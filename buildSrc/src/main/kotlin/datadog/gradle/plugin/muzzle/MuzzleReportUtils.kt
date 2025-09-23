package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.highest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.lowest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.resolveInstrumentationAndJarVersions
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.net.URLClassLoader
import java.util.SortedMap
import java.util.TreeMap
import java.util.function.BiFunction

internal object MuzzleReportUtils {
  private const val MUZZLE_DEPS_RESULTS = "muzzle-deps-results"
  private const val MUZZLE_TEST_RESULTS = "muzzle-test-results"

  fun dumpVersionRanges(project: Project) {
    val system: RepositorySystem = MuzzleMavenRepoUtils.newRepositorySystem()
    val session: RepositorySystemSession = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    val versions = TreeMap<String, TestedArtifact>()

    project.extensions.getByType<MuzzleExtension>().directives
      .filter { !it.isCoreJdk && !it.skipFromReport }
      .forEach { directive ->
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
    val filename = project.path.replaceFirst("^:".toRegex(), "").replace(":", "_")

    val verrsionsFile = project.rootProject.layout.buildDirectory.file("$MUZZLE_DEPS_RESULTS/$filename.csv")
    with(project.file(verrsionsFile)) {
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

  /**
   * Merges all muzzle report CSVs in the build directory into a single map and writes the merged results to a CSV.
   */
  fun mergeReports(project: Project) {
    val versionReports = project.fileTree(project.rootProject.layout.buildDirectory.dir(MUZZLE_DEPS_RESULTS)) {
      include("*.csv")
    }

    val map = TreeMap<String, TestedArtifact>()
    val versionScheme = GenericVersionScheme()

    versionReports.forEach {
      project.logger.info("Processing muzzle report: $it")
      it.useLines { lines ->
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
  fun generateResultsXML(project: Project, millis: Long) {
    val seconds = millis.toDouble() / 1000.0
    val name = "${project.path}:muzzle"
    val dirname = name.replaceFirst("^:".toRegex(), "").replace(":", "_")

    val dir = project.rootProject.layout.buildDirectory.dir("$MUZZLE_TEST_RESULTS/$dirname/results.xml")

    with(project.file(dir)) {
      parentFile.mkdirs()
      writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="$name" tests="1" id="0" time="$seconds">
          <testcase name="$name" time="$seconds">
          </testcase>
        </testsuite>
        """.trimIndent()
      )
      project.logger.info("Wrote muzzle results report to\n  $this")
    }
  }
}
