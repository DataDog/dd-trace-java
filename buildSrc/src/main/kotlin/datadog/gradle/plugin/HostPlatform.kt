package datadog.gradle.plugin

import java.util.Locale

object HostPlatform {
  @JvmStatic
  fun isLinuxArm64(): Boolean = isExpectedOs("linux") && isArm64()

  @JvmStatic
  fun isMacArm64(): Boolean = isExpectedOs("mac") && isArm64()

  private fun isExpectedOs(expectedOs: String): Boolean {
    val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    return osName.contains(expectedOs)
  }

  private fun isArm64(): Boolean {
    val osArch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
    return osArch.contains("aarch64") || osArch.contains("arm64")
  }
}
