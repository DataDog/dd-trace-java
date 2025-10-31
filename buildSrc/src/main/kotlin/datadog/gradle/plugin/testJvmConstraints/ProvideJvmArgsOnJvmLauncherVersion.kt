package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

class ProvideJvmArgsOnJvmLauncherVersion(
  @get:Internal
  val test: Test,

  @get:Input
  val applyFromVersion: JavaVersion,

  @get:Input
  val jvmArgsToApply: List<String>,

  @get:Input
  @get:Optional
  val additionalCondition: Provider<Boolean>
) : CommandLineArgumentProvider {

  override fun asArguments(): Iterable<String> {
    val launcherVersion = test.javaLauncher
      .map { JavaVersion.toVersion(it.metadata.languageVersion.asInt()) }
      .orElse(JavaVersion.current())
      .get()

    return if (launcherVersion.isCompatibleWith(applyFromVersion) && additionalCondition.getOrElse(true)) {
      jvmArgsToApply
    } else {
      emptyList()
    }
  }
}
