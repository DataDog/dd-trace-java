package datadog.gradle.plugin.liberty

import java.io.File
import javax.inject.Inject
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory

/**
 * Unzips the OpenLiberty runtime from the 'zipped' configuration so 'compileOnly' and test
 * classpaths can reference its jars.
 */
abstract class UnpackOpenLiberty : Copy() {
  /** Name of the configuration holding the OpenLiberty runtime zip. */
  @get:Input
  abstract val configurationName: Property<String>

  /** Directory the runtime is extracted into. */
  @get:OutputDirectory
  abstract val extractDir: DirectoryProperty

  /** Patterns, relative to [extractDir], selecting the jars exposed by [extractedJars]. */
  @get:Internal
  abstract val libJarIncludes: ListProperty<String>

  /** Patterns, relative to [extractDir], selecting the jars exposed by [wsServerJar]. */
  @get:Internal
  abstract val serverJarIncludes: ListProperty<String>

  @get:Inject
  protected abstract val archiveOperations: ArchiveOperations

  /** Extracted library jars matching [libJarIncludes]. */
  @get:Internal
  val extractedJars: Provider<FileTree> =
    extractDir.map { it.asFileTree.matching { include(libJarIncludes.get()) } }

  /**
   * Extracted server jars matching [serverJarIncludes]. Empty if the copy is filtered to exclude
   * them.
   */
  @get:Internal
  val wsServerJar: Provider<FileTree> =
    extractDir.map { it.asFileTree.matching { include(serverJarIncludes.get()) } }

  /**
   * The default server's server.xml inside [extractDir]. Only exists if the copy is not filtered to
   * exclude it.
   */
  @get:Internal
  val serverXmlFile: Provider<File> =
    extractDir.map { it.file("wlp/usr/servers/defaultServer/server.xml").asFile }

  init {
    configurationName.convention("zipped")
    libJarIncludes.convention(listOf("wlp/lib/*.jar"))
    serverJarIncludes.convention(listOf("wlp/bin/tools/ws-server.jar"))

    val configurations = project.configurations
    val zipFile: Provider<File> =
      configurationName.flatMap { name ->
        configurations.named(name).map { it.singleFile }
      }
    from(zipFile.map { archiveOperations.zipTree(it) })
    eachFile {
      path = path.replace(Regex("/templates/(servers/defaultServer/.+)"), "/usr/$1")
    }
    into(extractDir)
  }
}
