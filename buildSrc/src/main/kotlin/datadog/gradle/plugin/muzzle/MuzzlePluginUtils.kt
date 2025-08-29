package datadog.gradle.plugin.muzzle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType

internal val Project.mainSourceSet: SourceSet
  get() = project.extensions.getByType<SourceSetContainer>().named(SourceSet.MAIN_SOURCE_SET_NAME).get()

internal val Project.allMainSourceSet: List<SourceSet>
  get() = project.extensions.getByType<SourceSetContainer>().filter { it.name.startsWith(SourceSet.MAIN_SOURCE_SET_NAME) }
