package datadog.buildlogic.testcontainers

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

class ContainerImageArguments(
  @get:Internal val declarations: Map<String, String>,
  @get:Internal val imageEnvironment: Map<String, String>,
  @get:Internal val configurationFiles: List<File>,
  @get:Internal val resolver: Provider<ImageResolver>,
) : CommandLineArgumentProvider {
  // Read during Test input snapshotting, after skip predicates and before cache lookup.
  // Only the service reference is serialized by the configuration cache; its memo is per build.
  @get:Input
  val images: Map<String, String>
    get() = resolver.get().resolve(declarations, imageEnvironment, configurationFiles)

  override fun asArguments(): Iterable<String> = images.map { (name, image) -> "-D$name=$image" }
}
