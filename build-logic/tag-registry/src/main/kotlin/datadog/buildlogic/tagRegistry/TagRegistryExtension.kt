package datadog.buildlogic.tagRegistry

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/** Extension configuring the tag-registry generator inputs/outputs. */
abstract class TagRegistryExtension @Inject constructor(
  objectFactory: ObjectFactory,
  layout: ProjectLayout
) {
  /**
   * Define the location of the tag conventions
   */
  abstract val tagConventionsFile: RegularFileProperty

  /**
   * Destination of the generated sources, by convention under `build/generated/tag-registry`.
   */
  val destinationDirectory: DirectoryProperty = objectFactory.directoryProperty().convention(
    layout.buildDirectory.dir("generated/tag-registry")
  )
}
