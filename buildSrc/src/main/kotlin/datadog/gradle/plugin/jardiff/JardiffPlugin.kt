package datadog.gradle.plugin.jardiff

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * Registers the `compareToReferenceJar` task ([JardiffTask]) and wires its reusable inputs:
 * - the jardiff CLI classpath (resolved from [JardiffExtension.toolCoordinate]),
 * - the candidate archive — the module's main publishable jar (the shadow jar when the shadow
 *   plugin is applied, otherwise the plain jar),
 * - the reference jar, resolved from the `-PjardiffReferenceDir` project property by matching the
 *   candidate's file name inside that directory.
 *
 * Apply it with `id("dd-trace-java.jardiff")`.
 */
class JardiffPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create<JardiffExtension>("jardiff")
    extension.toolCoordinate.convention(DEFAULT_TOOL_COORDINATE)
    extension.mainClass.convention(DEFAULT_MAIN_CLASS)
    extension.mode.convention(DEFAULT_MODE)
    extension.additionalOptions.convention(emptyList())
    extension.reportDir.convention(project.layout.buildDirectory.dir("reports/jardiff"))
    extension.ignoreHashCheck.convention(false)

    // Use a detached configuration (created here, resolved only when the task runs)
    // 
    // This keeps the jardiff artifact out of `lockAllConfigurations()` dependency locking.
    // The dependency is added lazily, so overriding `jardiff.toolCoordinate` still takes effect.
    // The `@jar` requests the artifact only, because jardiff ships a self-contained "fat" CLI jar
    // under a non-default Gradle Module Metadata variant, the default resolution misses it.
    // Appending `@jar` ignores metadata and fetches that jar.
    val toolClasspath = project.configurations.detachedConfiguration().apply {
      dependencies.addLater(
        extension.toolCoordinate.map { coordinate ->
          val artifactOnly = if ('@' in coordinate) coordinate else "$coordinate@jar"
          project.dependencies.create(artifactOnly)
        },
      )
    }

    val projectDirectory = project.layout.projectDirectory
    val referenceDirProperty =
      project.providers.gradleProperty("jardiffReferenceDir").filter { it.isNotBlank() }

    val compare = project.tasks.register<JardiffTask>(COMPARE_TASK_NAME) {
      group = "verification"
      description = "Compares the built jar against a reference jar (typically the CI `build` " +
          "job artifact) using jardiff, failing if they differ. Set the reference with " +
          "--reference-jar=<path> or -PjardiffReferenceDir=<dir>."
      jardiffClasspath.convention(toolClasspath)
      mainClass.convention(extension.mainClass)
      mode.convention(extension.mode)
      additionalOptions.convention(extension.additionalOptions)
      reportDir.convention(extension.reportDir)
      ignoreHashCheck.convention(extension.ignoreHashCheck)
      // Ignore **/*.version by default, except under CI where the build and deploy
      // jobs share the same commit.
      ignoreVersionFiles.convention(
        project.providers.environmentVariable("CI").map { false }.orElse(true),
      )
      referenceJar.convention(
        // Use the same name as the candidate jar
        referenceDirProperty.flatMap { dir ->
          candidateJar.map { candidate -> projectDirectory.dir(dir).file(candidate.asFile.name) }
        },
      )
    }

    // candidateJar = the module's main publishable archive
    project.pluginManager.withPlugin("java") {
      compare.configure {
        candidateJar.convention(
          project.tasks.named<AbstractArchiveTask>("jar").flatMap { it.archiveFile },
        )
      }
    }
    project.pluginManager.withPlugin("com.gradleup.shadow") {
      compare.configure {
        candidateJar.set(
          project.tasks.named<AbstractArchiveTask>("shadowJar").flatMap { it.archiveFile },
        )
      }
    }
  }

  companion object {
    const val DEFAULT_TOOL_COORDINATE = "io.github.bric3.jardiff:jardiff-cli:0.2.0"

    const val DEFAULT_MAIN_CLASS = "io.github.bric3.jardiff.app.Main"

    const val DEFAULT_MODE = "--stat"

    const val COMPARE_TASK_NAME = "compareToReferenceJar"
  }
}
