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
  private const val MUZZLE_TEST_RESULTS = "muzzle-test-results"


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
