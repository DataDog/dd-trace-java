package datadog.gradle.plugin.instrument

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.withType

/**
 * Gradle plugin that applies ByteBuddy plugins to perform build-time bytecode instrumentation.
 *
 * This plugin appends a post-processing action to existing `main` compilation tasks. It allows
 * applying one or more ByteBuddy [net.bytebuddy.build.Plugin] implementations.
 *
 * Configuration:
 * 1. `buildTimeInstrumentation` extension: `plugins`: list of ByteBuddy plugin class names to apply
 *    `additionalClasspath`: additional classpath entries required to load plugins
 * 2. `buildTimeInstrumentationPlugin` configuration: dependencies containing ByteBuddy plugin implementations
 *
 * Example:
 * ```kotlin
 * buildTimeInstrumentation {
 *   plugins = listOf("com.example.MyByteBuddyPlugin", "com.example.AnotherPlugin")
 *   additionalClasspath = listOf(file("path/to/additional/classes"))
 * }
 *
 * dependencies {
 *   buildTimeInstrumentationPlugin("com.example:my-bytebuddy-plugin:1.0.0")
 *   buildTimeInstrumentationPlugin(project(
 *     path = ":some:project",
 *     configuration = "buildTimeInstrumentationToolingPlugins"
 *   ))
 * }
 * ```
 *
 * Requirements for ByteBuddy plugins:
 * 1. Must implement [net.bytebuddy.build.Plugin]
 * 2. Must have a constructor accepting a [java.io.File] parameter (target directory)
 * 3. Plugin classes must be available on `buildTimeInstrumentationPlugin` configuration
 *
 * @see BuildTimeInstrumentationExtension
 * @see InstrumentPostProcessingAction
 * @see ByteBuddyInstrumenter
 */
class BuildTimeInstrumentationPlugin : Plugin<Project> {
  private val logger = Logging.getLogger(BuildTimeInstrumentationPlugin::class.java)

  override fun apply(project: Project) {
    val extension =
      project.extensions.create<BuildTimeInstrumentationExtension>("buildTimeInstrumentation")

    project.configurations.register(BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION) {
      isVisible = false
      isCanBeConsumed = false
      isCanBeResolved = true
    }

    for (languagePluginId in listOf("java", "kotlin", "scala", "groovy")) {
      project.pluginManager.withPlugin(languagePluginId) {
        configurePostCompilationInstrumentation(languagePluginId, project, extension)
      }
    }
  }

  private fun configurePostCompilationInstrumentation(
    language: String,
    project: Project,
    extension: BuildTimeInstrumentationExtension
  ) {
    val sourceSets = project.extensions.getByType<SourceSetContainer>()
    // For any "main" source-set configure its compile task.
    sourceSets.configureEach {
      val sourceSetName = name
      logger.info("[BuildTimeInstrumentationPlugin] source-set: {}, language: {}", sourceSetName, language)

      if (!sourceSetName.startsWith(SourceSet.MAIN_SOURCE_SET_NAME)) {
        logger.debug(
          "[BuildTimeInstrumentationPlugin] Skipping non-main source set {} for language {}",
          sourceSetName,
          language
        )
        return@configureEach
      }

      val compileTaskName = getCompileTaskName(language)
      logger.info("[BuildTimeInstrumentationPlugin] compile task name: {}", compileTaskName)

      // For each _main_ compile task, append an instrumenting post-processing step.
      // Examples of compile tasks:
      // - compileJava,
      // - compileMain_java17Java,
      // - compileMain_jetty904Java,
      // - compileMain_play25Java,
      // - compileKotlin,
      // - compileScala,
      // - compileGroovy,
      project.tasks.withType<AbstractCompile>().matching {
        val sourceIsEmpty = it.source.isEmpty
        if (sourceIsEmpty) {
          logger.debug(
            "[BuildTimeInstrumentationPlugin] Skipping {} for source set {} as it has no source files",
            compileTaskName,
            sourceSetName
          )
        }
        it.name == compileTaskName && !sourceIsEmpty
      }.configureEach {
        logger.info(
          "[BuildTimeInstrumentationPlugin] Applying '{}' configuration as compile task input",
          BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION
        )
        inputs.files(project.configurations.named(BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION))

        // Compute optional Java version.
        val match = Regex("compileMain_(.+)Java").matchEntire(compileTaskName)
        var javaVersion: String? = null
        if (match != null) {
          val sourceSetSuffix = match.groupValues[1]
          if (Regex("java\\d+").matches(sourceSetSuffix)) {
            javaVersion = sourceSetSuffix.substring(4)
          }
        }
        // Null is not accepted for task inputs.
        val resolvedJavaVersion = javaVersion ?: DEFAULT_JAVA_VERSION
        inputs.property("javaVersion", resolvedJavaVersion)
        inputs.property("plugins", extension.plugins)
        inputs.files(extension.additionalClasspath)
        inputs.files(extension.includeClassDirectories)

        // Temporary location for raw (un-instrumented) classes.
        val tmpUninstrumentedClasses = project.objects.directoryProperty().value(
          project.layout.buildDirectory.dir("tmp/${name}-raw-classes")
        )

        // Class path to use for instrumentation post-processing.
        val instrumentingClassPath = project.objects.fileCollection().apply {
          setFrom(
            classpath,
            extension.additionalClasspath,
            tmpUninstrumentedClasses
          )
        }

        // This is where post-processing happens, i.e. where instrumentation is applied.
        doLast(
          "instrumentClasses",
          project.objects.newInstance<InstrumentPostProcessingAction>(
            resolvedJavaVersion,
            extension.plugins,
            instrumentingClassPath,
            destinationDirectory,
            tmpUninstrumentedClasses,
            extension.includeClassDirectories,
          )
        )

        logger.info(
          "[BuildTimeInstrumentationPlugin] Configured post-compile instrumentation for {} for source-set {}",
          compileTaskName,
          sourceSetName
        )
      }
    }
  }

  companion object {
    const val DEFAULT_JAVA_VERSION = "default"
    const val BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION = "buildTimeInstrumentationPlugin"
  }
}
