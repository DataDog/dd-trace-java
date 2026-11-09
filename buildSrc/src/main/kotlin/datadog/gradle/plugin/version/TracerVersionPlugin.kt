package datadog.gradle.plugin.version

import com.github.zafarkhaja.semver.Version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
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

    val theVersion = versionProvider(targetProject, extension)
    targetProject.allprojects {
      version = theVersion
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
      val currentBranchProvider = currentBranchProvider(repoWorkingDirectory)
      providerFactory.zip(
        currentBranchProvider.flatMap { currentBranch ->
          // Use --first-parent only on release branches so they stay anchored to their own
          // version line; feature branches pick up newer tags merged in from main.
          gitDescribeProvider(
            extension,
            repoWorkingDirectory,
            firstParent = currentBranch.startsWith("release/v")
          )
        },
        currentBranchProvider
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

  private fun currentBranchProvider(
    repoWorkingDirectory: File
  ): Provider<String> =
    gitCurrentBranchProvider(repoWorkingDirectory).zip(
      providerFactory.environmentVariable("CI_COMMIT_BRANCH").orElse("")
    ) { gitBranch, ciBranch ->
      // GitLab checks out a detached HEAD, where git reports "HEAD" instead of the branch name.
      // Pipelines that don't set CI_COMMIT_BRANCH to the release branch itself (merge requests,
      // merge queue working branches) still get the next minor version.
      if (gitBranch == "HEAD" && ciBranch.isNotBlank()) ciBranch else gitBranch
    }

  /**
   * Checks whether a build checked out on that exact tag may use it as the release version.
   *
   * In GitLab, only the pipeline of the tag itself builds a release version. Other pipelines on
   * the tagged commit, like the master one, build the next snapshot version, so they never publish
   * the release a second time.
   *
   * @param tag the tag, with its prefix
   * @return whether the tag can be used as the release version
   */
  private fun isReleaseTag(tag: String): Boolean =
    !providerFactory.environmentVariable("GITLAB_CI").isPresent ||
      tag == providerFactory.environmentVariable("CI_COMMIT_TAG").orNull

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
    repoWorkingDirectory: File,
    firstParent: Boolean,
  ) = providerFactory.of(GitCommandValueSource::class.java) {
    parameters {
      val tagPrefix = extension.tagVersionPrefix.get()
      gitCommand.addAll(
        "git",
        "describe",
        "--abbrev=8",
        "--tags",
        "--match=$tagPrefix[0-9].[0-9]*.[0-9]*",
      )

      if (firstParent) {
        gitCommand.add("--first-parent")
      }

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
    val hasLaterCommits = describeTrailer.isNotBlank() || !isReleaseTag("$tagPrefix$lastTagVersion")
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
        append(if (extension.useSnapshot.get() || describeTrailer.isBlank()) "-SNAPSHOT" else describeTrailer)
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
