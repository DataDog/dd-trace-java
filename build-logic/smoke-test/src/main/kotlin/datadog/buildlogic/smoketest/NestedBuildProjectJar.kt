package datadog.buildlogic.smoketest

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * A jar produced by the root build that needs to be forwarded into a [NestedGradleBuild].
 *
 * At execution time the task adds `-P${propertyName}=<absolute path of file>` to the nested
 * Gradle invocation, so the inner build script can pick it up via `findProperty(...)`.
 */
abstract class NestedBuildProjectJar {

  @get:Input
  abstract val propertyName: Property<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val file: RegularFileProperty
}

/** Classpath input for fixtures whose cached outputs can retain an earlier commit stamp. */
abstract class NestedBuildClasspathJar : NestedBuildProjectJar() {
  @get:Classpath
  abstract override val file: RegularFileProperty

  @get:Input
  abstract val version: Property<String>
}
