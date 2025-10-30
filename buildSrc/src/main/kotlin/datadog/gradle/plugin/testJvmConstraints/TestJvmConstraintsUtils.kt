package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.logging.Logging
import org.gradle.jvm.toolchain.JavaLauncher

private val logger = Logging.getLogger("TestJvmConstraintsUtils")

internal fun TestJvmConstraintsExtension.isJavaVersionAllowed(version: JavaVersion): Boolean {
  return isWithinAllowedRange(version)
}

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
  if (!isWithinAllowedRange(launcherVersion) && forceJdk.get().none { it.equals(testJvmName, ignoreCase = true) }) {
    return false
  }

  return true
}

private fun TestJvmConstraintsExtension.isWithinAllowedRange(currentJvmVersion: JavaVersion): Boolean {
  val definedMin = minJavaVersionForTests.isPresent
  val definedMax = maxJavaVersionForTests.isPresent

  if (definedMin && (minJavaVersionForTests.get()) > currentJvmVersion) {
    logger.info("isJavaVersionAllowedForProperty returns false b/o minProp=${minJavaVersionForTests.get()} is defined and greater than version=$currentJvmVersion")
    return false
  }

  if (definedMax && (maxJavaVersionForTests.get()) < currentJvmVersion) {
    logger.info("isJavaVersionAllowedForProperty returns false b/o maxProp=${maxJavaVersionForTests.get()} is defined and lower than version=$currentJvmVersion")
    return false
  }

  return true
}
