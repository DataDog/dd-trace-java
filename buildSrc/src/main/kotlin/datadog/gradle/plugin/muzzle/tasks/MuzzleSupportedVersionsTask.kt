package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.SupportedVersions
import org.gradle.api.JavaVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.net.URL
import java.net.URLClassLoader

/** Writes this instrumentation's supported versions, see [SupportedVersions]. */
abstract class MuzzleSupportedVersionsTask : AbstractMuzzleTask() {
  @get:Input
  abstract val reportDirectives: ListProperty<MuzzleDirective>

  /** The pass directives with a range to report. */
  @get:Internal
  val reportingDirectives: Provider<List<MuzzleDirective>> = reportDirectives.map { directives ->
    directives.filter { it.assertPass && it.isReportable && it.versions != null }
  }

  @get:Classpath
  abstract val instrumentationClasspath: ConfigurableFileCollection

  /** The lockfile pinning the library versions the tests run against. */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val lockfile: RegularFileProperty

  /** Each test runtime classpath configuration to the Java major versions CI runs it on. */
  @get:Input
  abstract val testedRuntimes: MapProperty<String, Set<Int>>

  @get:OutputFile
  abstract val supportedVersionsFile: RegularFileProperty

  init {
    description = "Generate this instrumentation's supported versions"
    reportDirectives.convention(emptyList())
  }

  @TaskAction
  fun generate() {
    val reporting = reportingDirectives.get()
    if (reporting.isEmpty()) {
      SupportedVersions.writeFragments(supportedVersionsFile.get().asFile, emptyList())
      return
    }
    val cp = instrumentationClasspath.map { it.toURI().toURL() }.toTypedArray<URL>()
    val directives = URLClassLoader(cp, null).use { cl ->
      val scanPlugin = cl.loadClass("datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin")
      val listMethod = scanPlugin.getMethod("listInstrumentationsEnabled", ClassLoader::class.java, String::class.java)
      reporting.map { directive ->
        @Suppress("UNCHECKED_CAST")
        val integrations = listMethod.invoke(null, cl, directive.name) as Map<String, Boolean>
        SupportedVersions.Directive(
          "${directive.group}:${directive.module}",
          directive.versions!!,
          directive.javaVersion?.let { JavaVersion.toVersion(it).majorVersion.toInt() },
          integrations,
        )
      }
    }
    val lockfile = lockfile.orNull?.asFile?.readText()?.let { SupportedVersions.parseLockfile(it) }.orEmpty()
    val fragments = SupportedVersions.fragments(directives, lockfile, testedRuntimes.get())
    SupportedVersions.writeFragments(supportedVersionsFile.get().asFile, fragments)
  }
}
