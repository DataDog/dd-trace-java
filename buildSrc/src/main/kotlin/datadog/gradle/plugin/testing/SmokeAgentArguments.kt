package datadog.gradle.plugin.testing

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

abstract class SmokeAgentArguments : CommandLineArgumentProvider {
  // Runtime classpath hashing includes .classdata resources, so changes to their bytes invalidate tests.
  @get:Classpath
  abstract val tracerJar: RegularFileProperty

  // Runtime classpath normalisation excludes the generated version resources.
  @get:Input
  abstract val tracerVersion: Property<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val diagnosticsJar: RegularFileProperty

  override fun asArguments(): Iterable<String> = listOf(
    "-Ddatadog.smoketest.agent.shadowJar.path=${tracerJar.get().asFile.absolutePath}",
    "-Ddatadog.smoketest.scopeDiagnostics.agent.path=${diagnosticsJar.get().asFile.absolutePath}",
  )
}
