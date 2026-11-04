package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Checks that no test task has contradictory test JVM constraints, such as a minimum Java version
 * above the maximum one. No JVM satisfies such constraints, so the task is skipped everywhere and
 * its tests never run, while the build still passes.
 */
@DisableCachingByDefault(because = "Fast check without outputs")
abstract class CheckTestJvmConstraints : DefaultTask() {
  companion object {
    /** The name of the task registered in each project with test JVM constraints. */
    const val TASK_NAME = "checkTestJvmConstraints"
  }

  /** The constraints of the test tasks to check. */
  @get:Nested
  abstract val testTasks: ListProperty<TestTaskConstraints>

  @TaskAction
  fun check() {
    val failures = testTasks.get()
      .filter { it.isContradictory() }
      .map { "  - ${it.taskPath.get()}: ${it.describe()}" }
    if (failures.isNotEmpty()) {
      throw GradleException(
        failures.joinToString(
          separator = "\n",
          prefix = "No JVM satisfies the testJvmConstraints of these test tasks, so their tests never run:\n",
        )
      )
    }
  }

  /** The test JVM constraints of a test task. */
  abstract class TestTaskConstraints {
    /** The path of the test task. */
    @get:Input
    abstract val taskPath: Property<String>

    /** See [TestJvmConstraintsExtension.minJavaVersion]. */
    @get:Input
    @get:Optional
    abstract val minJavaVersion: Property<JavaVersion>

    /** See [TestJvmConstraintsExtension.maxJavaVersion]. */
    @get:Input
    @get:Optional
    abstract val maxJavaVersion: Property<JavaVersion>

    /** See [TestJvmConstraintsExtension.includeJdk]. */
    @get:Input
    abstract val includeJdk: ListProperty<String>

    /** See [TestJvmConstraintsExtension.excludeJdk]. */
    @get:Input
    abstract val excludeJdk: ListProperty<String>

    /** See [TestJvmConstraintsExtension.forceJdk]. */
    @get:Input
    abstract val forceJdk: ListProperty<String>
  }
}

/**
 * Tells whether no JVM can satisfy the constraints. With `includeJdk`, no listed JVM is allowed;
 * an entry without a version counts as allowed. Without it, the minimum Java version is above the
 * maximum one and no `forceJdk` entry, which skips the version range, is left after `excludeJdk`.
 */
private fun CheckTestJvmConstraints.TestTaskConstraints.isContradictory(): Boolean {
  val min = minJavaVersion.orNull
  val max = maxJavaVersion.orNull
  val included = includeJdk.get()
  val excluded = excludeJdk.get()
  val forced = forceJdk.get()
  if (included.isNotEmpty()) {
    return included.none { name ->
      val version = TEST_JVM_NAME.matchEntire(name)?.groupValues?.get(2) ?: return@none true
      isTestJvmAllowed(name, { JavaVersion.toVersion(version) }, included, excluded, forced, min, max)
    }
  }
  return min != null && max != null && min > max &&
    forced.none { name -> excluded.none { it.equals(name, ignoreCase = true) } }
}

private fun CheckTestJvmConstraints.TestTaskConstraints.describe(): String = listOfNotNull(
  minJavaVersion.orNull?.let { "minJavaVersion=$it" },
  maxJavaVersion.orNull?.let { "maxJavaVersion=$it" },
  includeJdk.get().takeIf { it.isNotEmpty() }?.let { "includeJdk=$it" },
  excludeJdk.get().takeIf { it.isNotEmpty() }?.let { "excludeJdk=$it" },
  forceJdk.get().takeIf { it.isNotEmpty() }?.let { "forceJdk=$it" },
).joinToString(", ")
