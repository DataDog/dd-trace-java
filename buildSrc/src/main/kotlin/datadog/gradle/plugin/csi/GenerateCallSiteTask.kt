package datadog.gradle.plugin.csi

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

/** Generates call-site sources from compiled advice. */
@CacheableTask
abstract class GenerateCallSiteTask : JavaExec() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceDirectory: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classesDirectory: DirectoryProperty

  /** CSI owns and cleans all files in this directory. */
  @get:OutputDirectory
  abstract val destinationDirectory: DirectoryProperty

  @get:Classpath
  abstract val analysisClasspath: ConfigurableFileCollection

  @get:Input
  abstract val suffix: Property<String>

  @get:Input
  abstract val reporters: ListProperty<String>

  @get:Inject
  abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  override fun exec() {
    val inspectedClasspath = analysisClasspath.files

    if (logger.isInfoEnabled) {
      logger.info(
        "Aggregated CSI classpath:\n{}",
        inspectedClasspath.sorted().joinToString("\n") { it.toString() }
      )
    }

    val arguments = buildList {
      add(sourceDirectory.get().asFile.toString())
      add(classesDirectory.get().asFile.toString())
      add(destinationDirectory.get().asFile.toString())
      add(suffix.get())
      add(reporters.get().joinToString(","))

      // The remaining lines are the classpath inspected by CSI.
      addAll(inspectedClasspath.map { it.toString() })
    }

    val argumentFile = newTempFile(temporaryDir, "call-site-arguments")
    Files.write(argumentFile.toPath(), arguments)
    args(argumentFile.toString())

    // Generation must also remove sources whose advice was removed or no longer matches.
    fileSystemOperations.delete { delete(destinationDirectory) }
    Files.createDirectories(destinationDirectory.get().asFile.toPath())
    super.exec()
  }

  private fun newTempFile(folder: File, name: String): File {
    val file = File(folder, name)
    if (!file.exists() && !file.createNewFile()) {
      throw GradleException("Cannot create temporary file: $file")
    }
    file.deleteOnExit()
    return file
  }
}
