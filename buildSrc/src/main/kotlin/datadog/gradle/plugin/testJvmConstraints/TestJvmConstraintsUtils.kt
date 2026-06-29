package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.logging.Logging
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = Logging.getLogger("TestJvmConstraintsUtils")

internal fun TestJvmConstraintsExtension.isJavaVersionAllowed(version: JavaVersion): Boolean = withinAllowedRange(version)

internal fun TestJvmConstraintsExtension.isTestJvmAllowed(testJvmSpec: TestJvmSpec): Boolean {
  val testJvmName = testJvmSpec.normalizedTestJvm.get()

  val included = includeJdk.get()
  if (included.isNotEmpty() && included.none { it.equals(testJvmName, ignoreCase = true) }) {
    return false
  }

  val excluded = excludeJdk.get()
  if (excluded.isNotEmpty() && excluded.any { it.equals(testJvmName, ignoreCase = true) }) {
    return false
  }

  val launcherVersion = JavaVersion.toVersion(testJvmSpec.javaTestLauncher.get().metadata.languageVersion.asInt())
  if (!withinAllowedRange(launcherVersion) && forceJdk.get().none { it.equals(testJvmName, ignoreCase = true) }) {
    return false
  }

  return true
}

/**
 * When [TestJvmConstraintsExtension.nativeImageCapable] is `true`, verify the chosen test
 * launcher ships the `native-image` tool. The actual binary lives under `lib/svm/bin/` on
 * a GraalVM distribution; `bin/native-image` may be a symlink to it (or a `.cmd` shim on
 * Windows), so probe all three. Returns `true` when the requirement is unset or satisfied,
 * so the check is a safe no-op for tasks that haven't opted in.
 */
internal fun TestJvmConstraintsExtension.isNativeImageCapableTestJvm(launcher: JavaLauncher): Boolean {
  if (!nativeImageCapable.getOrElse(false)) return true
  return hasNativeImage(launcher.metadata.installationPath.asFile.toPath())
}

/**
 * Daemon-side counterpart to [isNativeImageCapableTestJvm], used when no `testJvm` was
 * selected — checks the running daemon's `java.home`. Same no-op semantics.
 */
internal fun TestJvmConstraintsExtension.isNativeImageCapableDaemon(): Boolean {
  if (!nativeImageCapable.getOrElse(false)) return true
  return hasNativeImage(File(System.getProperty("java.home")).toPath())
}

private fun hasNativeImage(installPath: Path): Boolean =
  Files.exists(installPath.resolve("lib/svm/bin/native-image")) ||
    Files.exists(installPath.resolve("bin/native-image")) ||
    Files.exists(installPath.resolve("bin/native-image.cmd"))

private fun TestJvmConstraintsExtension.withinAllowedRange(currentJvmVersion: JavaVersion): Boolean {
  val definedMin = minJavaVersion.isPresent
  val definedMax = maxJavaVersion.isPresent

  if (definedMin && (minJavaVersion.get()) > currentJvmVersion) {
    logger.info("'isWithinAllowedRange' returns false b/o testJvmConstraints.minJavaVersion=${minJavaVersion.get()} is defined and greater than test JVM version=$currentJvmVersion")
    return false
  }

  if (definedMax && (maxJavaVersion.get()) < currentJvmVersion) {
    logger.info("'isWithinAllowedRange' returns false because testJvmConstraints.maxJavaVersion=${maxJavaVersion.get()} is defined and lower than test JVM version=$currentJvmVersion")
    return false
  }

  return true
}
