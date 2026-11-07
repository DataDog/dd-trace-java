package datadog.buildlogic.smoketest

import org.gradle.api.Transformer
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import java.util.jar.JarFile

/**
 * A jar produced by the root build that needs to be forwarded into a [NestedGradleBuild].
 *
 * At execution time the task adds `-P${propertyName}=<absolute path of file>` to the nested
 * Gradle invocation, so the inner build script can pick it up via `findProperty(...)`.
 *
 * Cached JVM and native fixtures can retain earlier embedded commit stamps.
 */
abstract class NestedBuildProjectJar {

  @get:Input
  abstract val propertyName: Property<String>

  /**
   * Runtime classpath hashing tracks classes and resources: .classdata, manifests, instrumentation
   * indexes, configuration files and native libraries. Changes to normalised contents invalidate
   * application builds.
   */
  @get:Classpath
  abstract val file: RegularFileProperty

  /**
   * `project.version` is the base version (e.g. `1.69.0-SNAPSHOT`); `WriteVersionFile` appends `~<SHA>`.
   * Runtime-classpath normalisation excludes the complete `*.version` resources.
   * Tracking the base version separately invalidates application builds on version changes,
   * while changing only the commit stamp allows reuse when the other inputs match.
   */
  @get:Input
  abstract val version: Property<String>

  /** Gradle CI Visibility loads `dd-java-agent.version`, so its presence affects smoke results. */
  @get:Input
  val neededResourcePaths: Provider<List<String>>
    get() = file.map(existingResourcePaths("dd-java-agent.version"))

  private fun existingResourcePaths(vararg paths: String): Transformer<List<String>, RegularFile> =
    Transformer { file ->
      JarFile(file.asFile).use { jar ->
        paths.filter { jar.getJarEntry(it)?.isDirectory == false }.sorted()
      }
    }
}
