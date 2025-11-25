package datadog.gradle.plugin.csi

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import java.io.File
import javax.inject.Inject


/**
 * This extension allows to configure the Call Site Instrumenter plugin execution.
 */
abstract class CallSiteInstrumentationExtension @Inject constructor(
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
    layout.buildDirectory.dir("generated/sources/$CSI_SOURCE_SET")
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
      CALL_SITE_CONSOLE_REPORTER,
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
  val javaVersion: Property<JavaLanguageVersion> =
    objectFactory.property<JavaLanguageVersion>().convention(JavaLanguageVersion.current())

  /**
   * The JVM arguments to run the call site instrumenter.
   */
  val jvmArgs: ListProperty<String> =
    objectFactory.listProperty<String>().convention(listOf("-Xmx128m", "-Xms64m"))

  /**
   * The paths used to look for the call site instrumenter dependencies.
   *
   * The plugin includes by default **only** the `main` and `test` source sets, and their
   * related compilation classpath. As we don't want other test configurations by default.
   *
   * However, it's possible to contribute additional paths to look for dependencies.
   */
  val additionalPaths: ConfigurableFileCollection = objectFactory.fileCollection()
}
