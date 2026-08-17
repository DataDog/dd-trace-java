package datadog.gradle.plugin.tags

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Freshness gate: regenerates the tag registry into a scratch dir and byte-compares it against the
 * committed [committedDirectory]. Fails (pointing at {@code generateKnownTags}) if they differ, so a
 * stale commit of the generated sources can't slip through CI. Not cacheable -- it must actually run
 * the generator to catch drift, and it is cheap.
 */
abstract class VerifyKnownTagsTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  val domainYaml: RegularFileProperty = objects.fileProperty()

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  val overlayYaml: RegularFileProperty = objects.fileProperty()

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val committedDirectory: DirectoryProperty = objects.directoryProperty()

  @TaskAction
  fun verify() {
    val committed = committedDirectory.get().asFile
    val scratch = File(temporaryDir, "generated")
    scratch.deleteRecursively()
    TagRegistryGenerator.generate(domainYaml.get().asFile, overlayYaml.get().asFile, scratch)

    val diffs = ArrayList<String>()
    val freshFiles = scratch.walkTopDown().filter { it.isFile }.toList()
    for (fresh in freshFiles) {
      val rel = fresh.relativeTo(scratch).path
      val committedFile = File(committed, rel)
      when {
        !committedFile.exists() -> diffs.add("missing (not committed): $rel")
        committedFile.readText() != fresh.readText() -> diffs.add("out of date: $rel")
      }
    }
    val freshRel = freshFiles.map { it.relativeTo(scratch).path }.toSet()
    for (committedFile in committed.walkTopDown().filter { it.isFile }) {
      val rel = committedFile.relativeTo(committed).path
      if (rel !in freshRel) diffs.add("stale (no longer generated): $rel")
    }

    if (diffs.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Generated tag registry is out of date with tag-conventions.yaml:")
          diffs.forEach { appendLine("  - $it") }
          append("Run `./gradlew :internal-api:generateKnownTags` and commit the result.")
        })
    }
  }
}
