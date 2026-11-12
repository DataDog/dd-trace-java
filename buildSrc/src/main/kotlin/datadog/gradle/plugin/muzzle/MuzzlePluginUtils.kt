package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.withType

internal val Project.mainSourceSet: SourceSet
  get() = extensions.findByType<SourceSetContainer>()
    ?.named(MAIN_SOURCE_SET_NAME)?.get()
    ?: error("sourceSets not found, it means that java plugin was not applied")

internal val Project.allMainSourceSet: List<SourceSet>
  get() = extensions.findByType<SourceSetContainer>()
    ?.filter { it.name.startsWith(MAIN_SOURCE_SET_NAME) }
    .orEmpty()

internal val Project.pathSlug: String
  get() = path.removePrefix(":").replace(':', '_')

/** The enabled test tasks named after a source set, with their JVM constraints. */
internal fun Project.testSuites(): List<SupportedVersions.TestSuite> {
  val sourceSets = extensions.findByType<SourceSetContainer>()?.names.orEmpty()
  val testTasks = tasks.withType<Test>()
  // Read the names first: configuring a test task may register other tasks.
  return testTasks.names.filter { it in sourceSets }.mapNotNull { name ->
    val test = testTasks.getByName(name)
    val constraints = test.extensions.findByType<TestJvmConstraintsExtension>() ?: return@mapNotNull null
    if (!test.enabled) return@mapNotNull null
    SupportedVersions.TestSuite(
      name,
      constraints.minJavaVersion.orNull,
      constraints.maxJavaVersion.orNull,
      constraints.includeJdk.get(),
      constraints.excludeJdk.get(),
      constraints.forceJdk.get(),
    )
  }
}
