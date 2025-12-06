package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.MuzzleExtension
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.highest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.lowest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.resolveInstrumentationAndJarVersions
import datadog.gradle.plugin.muzzle.TestedArtifact
import datadog.gradle.plugin.muzzle.mainSourceSet
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import java.net.URL
import java.net.URLClassLoader
import java.util.TreeMap
import java.util.function.BiFunction

abstract class MuzzleMergeReportsTask : AbstractMuzzleReportTask() {
  init {
    description = "Print instrumentation version report"
  }

  @TaskAction
  fun dumpVersionRanges() {
    val system: RepositorySystem = MuzzleMavenRepoUtils.newRepositorySystem()
    val session: RepositorySystemSession = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    val versions = TreeMap<String, TestedArtifact>()
    project.extensions.getByType<MuzzleExtension>().directives
      .filter { !it.isCoreJdk && !it.skipFromReport }
      .forEach { directive ->
        val range = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, session)
        val cp = project.files(project.mainSourceSet.runtimeClasspath).map { it.toURI().toURL() }.toTypedArray<URL>()
        val cl = URLClassLoader(cp, null)
        val partials = resolveInstrumentationAndJarVersions(directive, cl, range.lowestVersion, range.highestVersion)

        partials.forEach { (key, value) ->
          versions.merge(
            key,
            value,
            BiFunction { x, y ->
              TestedArtifact(
                x.instrumentation,
                x.group,
                x.module,
                lowest(x.lowVersion, y.lowVersion),
                highest(x.highVersion, y.highVersion)
              )
            }
          )
        }
      }
    dumpVersionsToCsv(versions)
  }
}
