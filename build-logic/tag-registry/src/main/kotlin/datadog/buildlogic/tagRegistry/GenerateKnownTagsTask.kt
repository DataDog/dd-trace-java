package datadog.buildlogic.tagRegistry

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates KnownTags.java and assignment reports from tag-conventions.yaml.
 * Declares the inputs and outputs for Gradle's up-to-date checks and build cache.
 */
@CacheableTask
abstract class GenerateKnownTagsTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val domainYaml: RegularFileProperty

  @get:OutputDirectory abstract val destinationDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val outDir = destinationDirectory.get().asFile
    TagRegistryGenerator.generate(domainYaml.get().asFile, outDir)
    logger.lifecycle("tag-registry: generated -> $outDir")
  }
}
