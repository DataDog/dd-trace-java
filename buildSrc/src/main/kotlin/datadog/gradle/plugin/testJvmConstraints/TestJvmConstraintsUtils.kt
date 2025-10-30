package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.logging.Logging
import org.gradle.jvm.toolchain.JavaLauncher

private val logger = Logging.getLogger("TestJvmConstraintsUtils")

private fun TestJvmConstraintsExtension.isJavaVersionAllowedForProperty(currentJvmVersion: JavaVersion): Boolean {
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

internal fun TestJvmConstraintsExtension.isJavaVersionAllowed(version: JavaVersion): Boolean {
  return isJavaVersionAllowedForProperty(version)
}

/**
 * Convenience method to call [isJavaVersionAllowed]
 */
internal fun TestJvmConstraintsExtension.isJavaLauncherAllowed(javaLauncher: JavaLauncher): Boolean {
  val launcherVersion = JavaVersion.toVersion(javaLauncher.metadata.languageVersion.asInt())
  return isJavaVersionAllowed(launcherVersion)
}

internal fun TestJvmConstraintsExtension.isJdkForced(javaName: String): Boolean {
  return forceJdk.orNull?.any { it.equals(javaName, ignoreCase = true) } ?: false
}

internal fun TestJvmConstraintsExtension.isJdkExcluded(javaName: String): Boolean {
  return excludeJdk.get().any { it.equals(javaName, ignoreCase = true) }
}

internal fun TestJvmConstraintsExtension.isJdkIncluded(javaName: String): Boolean {
  val included = includeJdk.get()
  return when {
      included.isEmpty() -> true
      else -> included.any { it.equals(javaName, ignoreCase = true) }
  }
}
