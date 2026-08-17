package datadog.gradle.plugin.tags

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the committed tag registry (KnownTags.java + layout reports) from the language-agnostic
 * {@code tag-conventions.yaml} + the Java overlay. The actual emit lives in [TagRegistryGenerator];
 * this task just wires the inputs/outputs so Gradle can cache and up-to-date-check it.
 */
@CacheableTask
abstract class GenerateKnownTagsTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  val domainYaml: RegularFileProperty = objects.fileProperty()

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  val overlayYaml: RegularFileProperty = objects.fileProperty()

  @get:OutputDirectory val destinationDirectory: DirectoryProperty = objects.directoryProperty()

  @TaskAction
  fun generate() {
    val outDir = destinationDirectory.get().asFile
    TagRegistryGenerator.generate(domainYaml.get().asFile, overlayYaml.get().asFile, outDir)
    logger.lifecycle("tag-registry: generated -> $outDir")
  }
}
