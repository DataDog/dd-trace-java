package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.withType

/** The Java LTS versions known to Gradle, lowest first. CI tests every LTS version. */
private val LTS_VERSIONS: List<Int> = JavaVersion.values()
  .filter { it != JavaVersion.VERSION_HIGHER }
  .map { it.majorVersion.toInt() }
  .filter(::isLongTermSupport)

/**
 * Tells whether a Java version is a long-term support release: 8, 11, then every fourth release
 * from 17.
 *
 * @param majorVersion The major Java version.
 * @return `true` for an LTS version.
 */
internal fun isLongTermSupport(majorVersion: Int): Boolean =
  majorVersion == 8 || majorVersion == 11 || (majorVersion >= 17 && (majorVersion - 17) % 4 == 0)

/**
 * Returns the coverage test JVM of the project: the [TestJvmConstraintsExtension.coverageTestJvm]
 * of its `test` task when set, otherwise the lowest Java LTS version its `test` task accepts.
 *
 * @return The coverage test JVM, or `null` without a `test` task, when no LTS version is
 *   accepted, or when the tests need native-image.
 */
internal fun Project.coverageTestJvm(): String? = testTaskConstraints()?.coverageTestJvmOrLowestLts()

/**
 * Tells why the project has no valid coverage test JVM, when its coverage is measured.
 *
 * @return The problem, or `null` when the coverage test JVM is valid or coverage is not measured.
 */
internal fun Project.coverageTestJvmProblem(): String? {
  if (!pluginManager.hasPlugin("jacoco")) {
    return null
  }
  val testPath = tasks.withType<Test>().findByName(JavaPlugin.TEST_TASK_NAME)?.path ?: return null
  val constraints = testTaskConstraints() ?: return null
  if (constraints.nativeImageCapable.getOrElse(false)) {
    return null
  }
  val coverageTestJvm = constraints.coverageTestJvm.orNull
  return when {
    coverageTestJvm != null && !constraints.allows(coverageTestJvm) ->
      "$testPath: its coverageTestJvm=$coverageTestJvm is not allowed by its testJvmConstraints, so its coverage is never checked in CI."
    coverageTestJvm == null && constraints.lowestAllowedLts() == null ->
      "$testPath: no Java LTS version satisfies its testJvmConstraints, so its coverage is never checked in CI. Set testJvmConstraints.coverageTestJvm to the test JVM to check it on."
    else -> null
  }
}

private fun Project.testTaskConstraints(): TestJvmConstraintsExtension? =
  tasks.withType<Test>()
    .findByName(JavaPlugin.TEST_TASK_NAME)
    ?.extensions
    ?.findByType<TestJvmConstraintsExtension>()

internal fun TestJvmConstraintsExtension.coverageTestJvmOrLowestLts(): String? {
  if (nativeImageCapable.getOrElse(false)) {
    return null
  }
  return coverageTestJvm.orNull ?: lowestAllowedLts()
}

private fun TestJvmConstraintsExtension.lowestAllowedLts(): String? =
  LTS_VERSIONS.map(Int::toString).firstOrNull(::allows)

private fun TestJvmConstraintsExtension.allows(testJvm: String): Boolean {
  val version = TEST_JVM_NAME.matchEntire(testJvm)?.groupValues?.get(2) ?: return false
  return isTestJvmAllowed(
    testJvm,
    { JavaVersion.toVersion(version) },
    includeJdk.get(),
    excludeJdk.get(),
    forceJdk.get(),
    minJavaVersion.orNull,
    maxJavaVersion.orNull,
  )
}
