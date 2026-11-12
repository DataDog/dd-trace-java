package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.logging.Logging
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = Logging.getLogger("TestJvmConstraintsUtils")

/** Matches a `testJvm` name, like `8` or `zulu11`: group 1 is the distribution, group 2 the major version. */
internal val TEST_JVM_NAME = Regex("([a-zA-Z]*)([0-9]+)")

internal fun TestJvmConstraintsExtension.isJavaVersionAllowed(version: JavaVersion): Boolean =
  isWithinAllowedRange(version, minJavaVersion.orNull, maxJavaVersion.orNull)

internal fun TestJvmConstraintsExtension.isTestJvmAllowed(testJvmSpec: TestJvmSpec): Boolean =
  isTestJvmAllowed(
    testJvmSpec.normalizedTestJvm.get(),
    { JavaVersion.toVersion(testJvmSpec.requestedTestJvmLauncher.get().metadata.languageVersion.asInt()) },
    includeJdk.get(),
    excludeJdk.get(),
    forceJdk.get(),
    minJavaVersion.orNull,
    maxJavaVersion.orNull,
  )

/**
 * Tells whether a test JVM satisfies the test JVM constraints: it must be in [includeJdk] when set,
 * must not be in [excludeJdk], and must be within the [minJavaVersion]..[maxJavaVersion] range unless
 * [forceJdk] names it.
 *
 * @param testJvmName The `testJvm` name, like `8`, `zulu11` or `tip`.
 * @param testJvmVersion The Java version of the test JVM, only computed when the range is checked.
 * @param includeJdk The only test JVM names allowed, or empty to allow any.
 * @param excludeJdk The test JVM names never allowed.
 * @param forceJdk The test JVM names allowed regardless of the version range.
 * @param minJavaVersion The lowest Java version allowed, if any.
 * @param maxJavaVersion The highest Java version allowed, if any.
 * @return `true` if the test JVM is allowed.
 */
internal fun isTestJvmAllowed(
  testJvmName: String,
  testJvmVersion: () -> JavaVersion,
  includeJdk: List<String>,
  excludeJdk: List<String>,
  forceJdk: List<String>,
  minJavaVersion: JavaVersion?,
  maxJavaVersion: JavaVersion?,
): Boolean {
  if (includeJdk.isNotEmpty() && includeJdk.none { it.equals(testJvmName, ignoreCase = true) }) {
    return false
  }

  if (excludeJdk.any { it.equals(testJvmName, ignoreCase = true) }) {
    return false
  }

  return isWithinAllowedRange(testJvmVersion(), minJavaVersion, maxJavaVersion) ||
    forceJdk.any { it.equals(testJvmName, ignoreCase = true) }
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

private fun isWithinAllowedRange(
  currentJvmVersion: JavaVersion,
  minJavaVersion: JavaVersion?,
  maxJavaVersion: JavaVersion?,
): Boolean {
  if (minJavaVersion != null && minJavaVersion > currentJvmVersion) {
    logger.info("'isWithinAllowedRange' returns false b/o testJvmConstraints.minJavaVersion=$minJavaVersion is defined and greater than test JVM version=$currentJvmVersion")
    return false
  }

  if (maxJavaVersion != null && maxJavaVersion < currentJvmVersion) {
    logger.info("'isWithinAllowedRange' returns false because testJvmConstraints.maxJavaVersion=$maxJavaVersion is defined and lower than test JVM version=$currentJvmVersion")
    return false
  }

  return true
}
