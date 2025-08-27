package datadog.gradle.plugin.muzzle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType

internal fun createAgentClassPath(project: Project): FileCollection {
  project.logger.info("Creating agent classpath for $project")
  val cp = project.files()
  cp.from(project.allMainSourceSet.map { it.runtimeClasspath })

  if (project.logger.isInfoEnabled) {
    cp.forEach { project.logger.info("-- $it") }
  }
  return cp
}

internal fun createMuzzleClassPath(project: Project, muzzleTaskName: String): FileCollection {
  project.logger.info("Creating muzzle classpath for $muzzleTaskName")
  val cp = project.files()
  val config = if (muzzleTaskName == "muzzle") {
    project.configurations.named("compileClasspath").get()
  } else {
    project.configurations.named(muzzleTaskName).get()
  }
  cp.from(config)
  if (project.logger.isInfoEnabled) {
    cp.forEach { project.logger.info("-- $it") }
  }
  return cp
}

internal val Project.mainSourceSet: SourceSet
  get() = project.extensions.getByType<SourceSetContainer>().named(SourceSet.MAIN_SOURCE_SET_NAME).get()

internal val Project.allMainSourceSet: List<SourceSet>
  get() = project.extensions.getByType<SourceSetContainer>().filter { it.name.startsWith(SourceSet.MAIN_SOURCE_SET_NAME) }
