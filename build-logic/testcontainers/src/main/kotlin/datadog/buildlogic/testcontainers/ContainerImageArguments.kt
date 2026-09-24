package datadog.buildlogic.testcontainers

import org.gradle.api.provider.Provider
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

class ContainerImageArguments(
  @get:Nested @get:Optional val containers: Provider<ContainerImageInputs>,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> =
    containers.orNull
      ?.images
      ?.map { (name, image) -> "-D$name=$image" }
      .orEmpty()
}

class ContainerImageInputs(
  @get:Internal val declarations: Map<String, String>,
  @get:Internal val imageEnvironment: Map<String, String>,
  @get:Internal val configurationFiles: List<File>,
  @get:ServiceReference("testContainerImageResolver") val resolver: Provider<ImageResolver>,
  @get:ServiceReference("testcontainersLimit") val limit: Provider<TestcontainersLimitService>,
) {
  // Read during Test input snapshotting, after skip predicates and before cache lookup.
  // Only the service reference is serialized by the configuration cache; its memo is per build.
  @get:Input
  val images: Map<String, String>
    get() = resolver.get().resolve(declarations, imageEnvironment, configurationFiles)
}
