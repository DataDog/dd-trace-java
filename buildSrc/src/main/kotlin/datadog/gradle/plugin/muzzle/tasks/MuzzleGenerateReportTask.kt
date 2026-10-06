package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.highest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.lowest
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.resolveInstrumentationAndJarVersions
import datadog.gradle.plugin.muzzle.TestedArtifact
import datadog.gradle.plugin.muzzle.pathSlug
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.net.URL
import java.net.URLClassLoader
import java.util.TreeMap
import java.util.function.BiFunction

abstract class MuzzleGenerateReportTask : AbstractMuzzleReportTask() {
  @get:Input
  abstract val reportDirectives: ListProperty<MuzzleDirective>

  @get:Classpath
  abstract val instrumentationClasspath: ConfigurableFileCollection

  @get:Internal
  val reportingDirectives: Provider<List<MuzzleDirective>> = reportDirectives.map { directives ->
    directives.filter { !it.isCoreJdk && !it.skipFromReport }
  }

  init {
    description = "Generate this instrumentation's dependency version report"
    reportDirectives.convention(emptyList())
    versionsFile.convention(project.layout.buildDirectory.file("$MUZZLE_DEPS_RESULTS/${project.pathSlug}.csv"))
    // Repository metadata can change without any local task input changing.
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun dumpVersionRanges() {
    val system: RepositorySystem = MuzzleMavenRepoUtils.newRepositorySystem()
    val session: RepositorySystemSession = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    val versions = TreeMap<String, TestedArtifact>()
    reportingDirectives.get().forEach { directive ->
      val range = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, session)
      val cp = instrumentationClasspath.map { it.toURI().toURL() }.toTypedArray<URL>()
      val partials = URLClassLoader(cp, null).use { cl ->
        resolveInstrumentationAndJarVersions(directive, cl, range.lowestVersion, range.highestVersion)
      }
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
    dumpVersionsToCsv(versions)
  }
}
