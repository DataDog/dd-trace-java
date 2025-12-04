package datadog.gradle.plugin.muzzle

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.findByType

internal val Project.mainSourceSet: SourceSet
  get() =
    extensions
      .findByType<SourceSetContainer>()
      ?.named(MAIN_SOURCE_SET_NAME)
      ?.get()
      ?: error("sourceSets not found, it means that java plugin was not applied")

internal val Project.allMainSourceSet: List<SourceSet>
  get() =
    extensions
      .findByType<SourceSetContainer>()
      ?.filter { it.name.startsWith(MAIN_SOURCE_SET_NAME) }
      .orEmpty()

internal val Project.pathSlug: String
  get() = path.removePrefix(":").replace(':', '_')
