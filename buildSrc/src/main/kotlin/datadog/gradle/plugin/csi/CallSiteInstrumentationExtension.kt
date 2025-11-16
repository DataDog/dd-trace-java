package datadog.gradle.plugin.csi

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSet.TEST_SOURCE_SET_NAME
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import java.io.File
import java.util.Locale
import javax.inject.Inject


/**
 * This extension allows to configure the Call Site Instrumenter plugin execution.
 */
abstract class CallSiteInstrumentationExtension @Inject constructor(
  project: Project,
  objectFactory: ObjectFactory,
  layout: ProjectLayout
) {
  companion object {
    const val CALL_SITE_CLASS_SUFFIX = "CallSite"
    const val CALL_SITE_CONSOLE_REPORTER = "CONSOLE"
    const val CALL_SITE_ERROR_CONSOLE_REPORTER = "ERROR_CONSOLE"
  }

  /**
   * The location of the source code to generate call site ({@code <project>/src/main/java} by default).
   */
  val srcFolder: DirectoryProperty = objectFactory.directoryProperty().convention(
    layout.projectDirectory.dir("src").dir("main").dir("java")
  )

  /**
   * The location to generate call site source code ({@code <project>/build/generated/sources/csi} by default).
   */
  val targetFolder: DirectoryProperty = objectFactory.directoryProperty().convention(
    layout.buildDirectory.dir("generated${File.separatorChar}sources${File.separatorChar}csi")
  )

  /**
   * The generated call site source file suffix (#CALL_SITE_CLASS_SUFFIX by default).
   */
  val suffix: Property<String> = objectFactory.property<String>().convention(CALL_SITE_CLASS_SUFFIX)

  /**
   * The reporters to use after call site instrumenter run (only #CALL_SITE_CONSOLE_REPORTER and #CALL_SITE_ERROR_CONSOLE_REPORTER supported for now).
   */
  val reporters: ListProperty<String> = objectFactory.listProperty<String>().convention(
    listOf(
      CALL_SITE_ERROR_CONSOLE_REPORTER
    )
  )

  /**
   * The location of the dd-trace-java project to look for the call site instrumenter (optional, current project root folder used if not set).
   */
  abstract val rootFolder: Property<File>

  /**
   * The JVM to use to run the call site instrumenter (optional, default JVM used if not set).
   */
  abstract val javaVersion: Property<JavaLanguageVersion>

  /**
   * The JVM arguments to run the call site instrumenter.
   */
  val jvmArgs: ListProperty<String> =
    objectFactory.listProperty<String>().convention(listOf("-Xmx128m", "-Xms64m"))

  /**
   * The configurations to use to look for the call site instrumenter dependencies.
   *
   * By default, includes all `main*` source sets, but only the `test`
   * (as wee don't want other test configurations by default).
   */
  val configurations: ListProperty<Configuration> = objectFactory.listProperty<Configuration>().convention(
    project.provider {
      project.configurations.matching {
        // Includes all main* source sets, but only the test (as wee don;t want other )
        // * For main => runtimeClasspath, compileClasspath
        // * For test => testRuntimeClasspath, testCompileClasspath
        // * For other main* => "main_javaXXRuntimeClasspath", "main_javaXXCompileClasspath"

        when (it.name) {
          // Regular main and test source sets
          RUNTIME_CLASSPATH_CONFIGURATION_NAME,
          COMPILE_CLASSPATH_CONFIGURATION_NAME,
          TEST_SOURCE_SET_NAME + RUNTIME_CLASSPATH_CONFIGURATION_NAME.capitalize(),
          TEST_SOURCE_SET_NAME + COMPILE_CLASSPATH_CONFIGURATION_NAME.capitalize() -> true

          // Other main_javaXX source sets
          else -> {
            it.name.startsWith(MAIN_SOURCE_SET_NAME) &&
                (it.name.endsWith(RUNTIME_CLASSPATH_CONFIGURATION_NAME, ignoreCase = true) ||
                    it.name.endsWith(COMPILE_CLASSPATH_CONFIGURATION_NAME, ignoreCase = true))
          }
        }
      }
    }
  )

  private fun String.capitalize(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase(
      Locale.getDefault()
    ) else it.toString()
  }
}
