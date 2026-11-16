package datadog.gradle.plugin.smoketest

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.util.jar.JarFile

abstract class SmokeAgentArguments : CommandLineArgumentProvider {
  // Hash classes and resources using the consuming project's runtime-classpath normalisation.
  @get:Classpath
  abstract val tracerJar: RegularFileProperty

  // Normalisation excludes *.version contents, so track the base version separately from the commit stamp.
  @get:Input
  abstract val tracerVersion: Property<String>

  // Missing version resources change CI Visibility's version reporting even when their contents are ignored.
  @get:Input
  val neededResourcePaths: Provider<List<String>>
    get() = tracerJar.map { file ->
      JarFile(file.asFile).use { jar ->
        listOf("dd-java-agent.version", "dd-trace-api.version", "trace/dd-trace-core.version")
          .filter { jar.getJarEntry(it) != null }
          .sorted()
      }
    }

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val diagnosticsJar: RegularFileProperty

  @get:Input
  abstract val quiescenceMillis: Property<String>

  @get:Internal
  abstract val buildDirectory: DirectoryProperty

  override fun asArguments(): Iterable<String> = listOf(
    "-Ddatadog.smoketest.agent.shadowJar.path=${tracerJar.get().asFile.absolutePath}",
    "-Ddatadog.smoketest.scopeDiagnostics.agent.path=${diagnosticsJar.get().asFile.absolutePath}",
    "-Ddatadog.smoketest.scopeDiagnostics.quiescenceMillis=${quiescenceMillis.get()}",
    "-Ddatadog.smoketest.builddir=${buildDirectory.get().asFile.absolutePath}",
  )
}
