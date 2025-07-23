package datadog.gradle.plugin.version

import com.github.zafarkhaja.semver.Version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

class TracerVersionPlugin @Inject constructor(
  private val providerFactory: ProviderFactory,
) : Plugin<Project> {
  private val logger = Logging.getLogger(TracerVersionPlugin::class.java)

  override fun apply(targetProject: Project) {
    if (targetProject.rootProject != targetProject) {
      throw IllegalStateException("Only root project can apply plugin")
    }

    val extension = targetProject.extensions.create("tracerVersion", TracerVersionExtension::class.java)
    val versionProvider = versionProvider(targetProject, extension)

    targetProject.allprojects {
      version = versionProvider
    }
  }

  private fun versionProvider(
    targetProject: Project,
    extension: TracerVersionExtension
  ): String {
    val workingDirectory = targetProject.projectDir

    val buildVersion: String = if (!workingDirectory.resolve(".git").exists()) {
      extension.defaultVersion.get()
    } else {
      providerFactory.of(GitDescribeValueSource::class.java) {
        parameters {
          this.tagVersionPrefix.set(extension.tagVersionPrefix)
          this.showDirty.set(extension.detectDirty)
          this.workingDirectory.set(workingDirectory)
        }
      }.map {
        toTracerVersion(it.trim(), extension)
      }.get()
    }

    logger.lifecycle("Tracer build version: {}", buildVersion)
    return buildVersion
  }

  private fun toTracerVersion(describeString: String, extension: TracerVersionExtension): String {
    logger.info("Git describe output: {}", describeString)

    val tagPrefix = extension.tagVersionPrefix.get()
    val tagRegex = Regex("$tagPrefix(\\d+\\.\\d+\\.\\d+)(.*)")
    val matchResult = tagRegex.find(describeString)
      ?: return extension.defaultVersion.get()

    val (lastTagVersion, describeTrailer) = matchResult.destructured
    val hasLaterCommits = describeTrailer.isNotBlank()
    val version = Version.parse(lastTagVersion).let {
      if (hasLaterCommits) {
        it.nextMinorVersion()
      } else {
        it
      }
    }

    return buildString {
      append(tagPrefix)
      append(version.toString())

      if (hasLaterCommits) {
        append(if (extension.useSnapshot.get()) "-SNAPSHOT" else describeTrailer)
      }

      if (describeTrailer.endsWith("-dirty")) {
        append("-dirty")
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
      .convention(false)
  }
}
