package datadog.gradle.plugin.instrument

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile

import java.util.regex.Matcher

/**
 * instrument<Language> task plugin which performs build-time instrumentation of classes.
 */
@SuppressWarnings('unused')
class InstrumentPlugin implements Plugin<Project> {
  public static final String DEFAULT_JAVA_VERSION = 'default'
  public static final String INSTRUMENT_PLUGIN_CLASSPATH_CONFIGURATION = 'instrumentPluginClasspath'
  private final Logger logger = Logging.getLogger(InstrumentPlugin)

  @Override
  void apply(Project project) {
    InstrumentExtension extension = project.extensions.create('instrument', InstrumentExtension)
    project.configurations.register(INSTRUMENT_PLUGIN_CLASSPATH_CONFIGURATION)

    project.pluginManager.withPlugin("java") { configurePostCompilationInstrumentation("java", project, extension) }
    project.pluginManager.withPlugin("kotlin") { configurePostCompilationInstrumentation("kotlin", project, extension) }
    project.pluginManager.withPlugin("scala") { configurePostCompilationInstrumentation("scala", project, extension) }
    project.pluginManager.withPlugin("groovy") { configurePostCompilationInstrumentation("groovy", project, extension) }
  }

  private void configurePostCompilationInstrumentation(String language, Project project, InstrumentExtension extension) {
    project.extensions.configure(SourceSetContainer) { SourceSetContainer sourceSets ->
      // For any "main" source-set configure its compile task
      sourceSets.configureEach { SourceSet sourceSet ->
        def sourceSetName = sourceSet.name
        logger.info("[InstrumentPlugin] source-set: $sourceSetName, language: $language")

        if (!sourceSetName.startsWith(SourceSet.MAIN_SOURCE_SET_NAME)) {
          logger.debug("[InstrumentPlugin] Skipping non-main source set {} for language {}", sourceSetName, language)
          return
        }

        def compileTaskName = sourceSet.getCompileTaskName(language)
        logger.info("[InstrumentPlugin] compile task name: " + compileTaskName)

        // For each compile task, append an instrumenting post-processing step
        // Examples of compile tasks:
        // - compileJava,
        // - compileMain_java17Java,
        // - compileMain_jetty904Java,
        // - compileMain_play25Java,
        // - compileKotlin,
        // - compileScala,
        // - compileGroovy,
        project.tasks.withType(AbstractCompile).matching {
          it.name == compileTaskName && !it.source.isEmpty()
        }.configureEach {
          logger.info('[InstrumentPlugin] Applying instrumentPluginClasspath configuration as compile task input')
          it.inputs.files(project.configurations.named(INSTRUMENT_PLUGIN_CLASSPATH_CONFIGURATION))

          if (it.source.isEmpty()) {
            logger.debug("[InstrumentPlugin] Skipping $compileTaskName for source set $sourceSetName as it has no source files")
            return
          }

          // Compute optional Java version
          Matcher versionMatcher = compileTaskName =~ /compileMain_(.+)Java/
          String sourceSetSuffix = null
          String javaVersion = null
          if (versionMatcher.matches()) {
            sourceSetSuffix = versionMatcher.group(1)
            if (sourceSetSuffix ==~ /java\d+/) {
              javaVersion = sourceSetSuffix[4..-1]
            }
          }
          javaVersion = javaVersion ?: DEFAULT_JAVA_VERSION // null not accepted
          it.inputs.property("javaVersion", javaVersion)

          it.inputs.property("plugins", extension.plugins)

          it.inputs.files(extension.additionalClasspath)

          // Temporary location for raw (un-instrumented) classes
          DirectoryProperty tmpUninstrumentedClasses = project.objects.directoryProperty().value(
            project.layout.buildDirectory.dir("tmp/${it.name}-raw-classes")
          )

          // Class path to use for instrumentation post-processing
          ConfigurableFileCollection instrumentingClassPath = project.objects.fileCollection()
          instrumentingClassPath.setFrom(
            it.classpath,
            extension.additionalClasspath,
            tmpUninstrumentedClasses
          )

          // This were the post processing happens, i.e. were the instrumentation is applied
          it.doLast(
            "instrumentClasses",
            project.objects.newInstance(
              InstrumentPostProcessingAction,
              javaVersion,
              extension.plugins,
              instrumentingClassPath,
              it.destinationDirectory,
              tmpUninstrumentedClasses
            )
          )
          logger.info("[InstrumentPlugin] Configured post-compile instrumentation for $compileTaskName for source-set $sourceSetName")
        }
      }
    }
  }
}
