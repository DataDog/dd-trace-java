package datadog.gradle.plugin.csi

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import javax.inject.Inject

/** Generates call-site sources from compiled advice. */
@CacheableTask
abstract class GenerateCallSiteTask : JavaExec() {
  @get:OutputDirectory
  abstract val destinationDirectory: DirectoryProperty

  @get:Inject
  abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  override fun exec() {
    // Generation must also remove sources whose advice was removed or no longer matches.
    fileSystemOperations.delete { delete(destinationDirectory) }
    Files.createDirectories(destinationDirectory.get().asFile.toPath())
    super.exec()
  }
}
