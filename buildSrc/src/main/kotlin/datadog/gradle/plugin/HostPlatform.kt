package datadog.gradle.plugin

import java.io.File
import java.util.Locale

object HostPlatform {
  @JvmStatic
  fun isWindows(): Boolean = isExpectedOs("windows")

  /** Returns the complete command needed to run the repository Maven wrapper on this host. */
  @JvmStatic
  fun mavenWrapperCommand(directory: File, arguments: List<String>): List<String> =
    mavenWrapperCommand(directory, arguments, System.getProperty("os.name", ""))

  @JvmStatic
  fun isLinuxArm64(): Boolean = isExpectedOs("linux") && isArm64()

  @JvmStatic
  fun isMacArm64(): Boolean = isExpectedOs("mac") && isArm64()

  private fun isExpectedOs(expectedOs: String): Boolean {
    val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    return osName.contains(expectedOs)
  }

  internal fun mavenWrapperCommand(
    directory: File,
    arguments: List<String>,
    osName: String,
  ): List<String> {
    val windows = osName.lowercase(Locale.ROOT).contains("windows")
    val wrapper = File(directory, if (windows) "mvnw.cmd" else "mvnw").absolutePath
    val command = if (windows) listOf("cmd", "/c", wrapper) else listOf(wrapper)
    return command + arguments
  }

  private fun isArm64(): Boolean {
    val osArch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
    return osArch.contains("aarch64") || osArch.contains("arm64")
  }
}
