package datadog.gradle.plugin.version

import com.github.zafarkhaja.semver.Version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.property
import java.io.File
import javax.inject.Inject

class TracerVersionPlugin @Inject constructor(
  private val providerFactory: ProviderFactory,
) : Plugin<Project> {
  private val logger = Logging.getLogger(TracerVersionPlugin::class.java)

  override fun apply(targetProject: Project) {
    if (targetProject.rootProject != targetProject) {
      throw IllegalStateException("Only root project can apply plugin")
    }
    targetProject.extensions.create("tracerVersion", TracerVersionExtension::class.java)
    val extension = targetProject.extensions.getByType(TracerVersionExtension::class.java)

    extension.detectDirty.set(
     providerFactory.gradleProperty("tracerVersion.dirtiness")
       .map { it.trim().toBoolean() }
       .orElse(false)
    )

    extension.versionQualifier.set(
      providerFactory.gradleProperty("tracerVersion.qualifier")
    )

    val versionProvider = versionProvider(targetProject, extension)
    targetProject.allprojects {
      version = versionProvider
    }
  }

  private fun versionProvider(
    targetProject: Project,
    extension: TracerVersionExtension
  ): String {
    val repoWorkingDirectory = targetProject.rootDir

    val buildVersion: String = if (!repoWorkingDirectory.resolve(".git").exists()) {
      // Not a git repository
      extension.defaultVersion.get()
    } else {
      providerFactory.zip(
        gitDescribeProvider(extension, repoWorkingDirectory),
        gitCurrentBranchProvider(repoWorkingDirectory)
      ) { describeString, currentBranch ->
        toTracerVersion(describeString, extension) {
          when {
            currentBranch.startsWith("release/v") -> {
              logger.info("Incrementing patch because release branch : $currentBranch")
              nextPatchVersion()
            }
            else -> {
              logger.info("Incrementing minor")
              nextMinorVersion()
            }
          }
        }
      }.get()
    }

    logger.lifecycle("Tracer build version: {}", buildVersion)
    return buildVersion
  }

  private fun gitCurrentBranchProvider(
    repoWorkingDirectory: File
  ) = providerFactory.of(GitCommandValueSource::class.java) {
    parameters {
      gitCommand.addAll(
        "git",
        "rev-parse",
        "--abbrev-ref",
        "HEAD",
      )
      workingDirectory.set(repoWorkingDirectory)
    }
  }

  private fun gitDescribeProvider(
    extension: TracerVersionExtension,
    repoWorkingDirectory: File
  ) = providerFactory.of(GitCommandValueSource::class.java) {
    parameters {
      val tagPrefix = extension.tagVersionPrefix.get()
      gitCommand.addAll(
        "git",
        "describe",
        "--abbrev=8",
        "--tags",
        "--first-parent",
        "--match=$tagPrefix[0-9].[0-9]*.[0-9]*",
      )

      if (extension.detectDirty.get()) {
        gitCommand.add("--dirty")
      }

      workingDirectory.set(repoWorkingDirectory)
    }
  }

  private fun toTracerVersion(describeString: String, extension: TracerVersionExtension, nextVersion: Version.() -> Version): String {
    logger.info("Git describe output: {}", describeString)

    val tagPrefix = extension.tagVersionPrefix.get()
    val tagRegex = Regex("$tagPrefix(\\d+\\.\\d+\\.\\d+)(.*)")
    val matchResult = tagRegex.find(describeString)
      ?: return extension.defaultVersion.get()

    val (lastTagVersion, describeTrailer) = matchResult.destructured
    val hasLaterCommits = describeTrailer.isNotBlank()
    val version = Version.parse(lastTagVersion).let {
      if (hasLaterCommits) {
        it.nextVersion()
      } else {
        it
      }
    }

    return buildString {
      append(version.toString())

      // Add optional version qualifier (e.g., "-ddprof")
      if (extension.versionQualifier.isPresent) {
        val qualifier = extension.versionQualifier.get()
        if (qualifier.isNotBlank()) {
          append("-").append(qualifier)
        }
      }

      if (hasLaterCommits) {
        append(if (extension.useSnapshot.get()) "-SNAPSHOT" else describeTrailer)
      }

      if (describeTrailer.endsWith("-dirty")) {
        append(if (extension.useSnapshot.get()) "-DIRTY" else "-dirty")
      }
    }
  }

  open class TracerVersionExtension @Inject constructor(objectFactory: ObjectFactory) {
    val defaultVersion = objectFactory.property(String::class)
      .convention("0.1.0-SNAPSHOT")
    val tagVersionPrefix = objectFactory.property(String::class)
      .convention("v")
    val useSnapshot = objectFactory.property(Boolean::class)
      .convention(true)
    val detectDirty = objectFactory.property(Boolean::class)
    val versionQualifier = objectFactory.property(String::class)
  }
}
