package datadog.gradle.plugin

import org.gradle.api.Project
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.platform.Architecture
import org.gradle.platform.BuildPlatform
import org.gradle.platform.OperatingSystem
import org.gradle.platform.internal.CurrentBuildPlatform

/**
 * Helpers built on Gradle's [BuildPlatform].
 *
 * The public [BuildPlatform] interface is not directly available elsewhere.
 * Build logic obtains it from the internal [CurrentBuildPlatform] service,
 * resolved from a [Project] via `serviceOf` — see the [Project] overloads below.
 */
fun BuildPlatform.isLinuxArm64(): Boolean =
  operatingSystem == OperatingSystem.LINUX && architecture == Architecture.AARCH64

fun BuildPlatform.isMacArm64(): Boolean =
  operatingSystem == OperatingSystem.MAC_OS && architecture == Architecture.AARCH64

fun Project.isLinuxArm64(): Boolean =
  serviceOf<CurrentBuildPlatform>().toBuildPlatform().isLinuxArm64()

fun Project.isMacArm64(): Boolean =
  serviceOf<CurrentBuildPlatform>().toBuildPlatform().isMacArm64()
