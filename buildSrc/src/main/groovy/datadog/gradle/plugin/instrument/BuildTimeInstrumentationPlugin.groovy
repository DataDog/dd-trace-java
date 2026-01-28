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
 * Gradle plugin that applies ByteBuddy plugins to perform build-time bytecode instrumentation.
 *
 * <p>This plugin appends a post-processing action to existing <strong>main</strong> compilation
 * tasks. This plugin allows to apply one or more ByteBuddy {@link net.bytebuddy.build.Plugin}
 * implementations.
 *
 * <h3>Configuration:</h3>
 * There are two main configuration points:
 * <ul>
 *   <li>The {@code buildTimeInstrumentation} extension, which allows to specify:
 *   <ul>
 *     <li>The list of ByteBuddy plugin class names to apply</li>
 *     <li>Additional classpath entries required to load the plugins</li>
 *   </ul>
 *   </li>
 *   <li>The {@code buildTimeInstrumentationPlugin} configuration, which allows to specify
 *   dependencies containing the ByteBuddy plugin implementations</li>
 * </ul>
 * <p>Example configuration:
 *
 * <pre>
 * buildTimeInstrumentation {
 *   plugins = ['com.example.MyByteBuddyPlugin', 'com.example.AnotherPlugin']
 *   additionalClasspath = [file('path/to/additional/classes')]
 * }
 *
 * dependencies {
 *   buildTimeInstrumentationPlugin 'com.example:my-bytebuddy-plugin:1.0.0'
 *   buildTimeInstrumentationPlugin project(path: ':some:project', configuration: 'buildTimeInstrumentationPlugin')
 * }
 * </pre>
 *
 * <h3>Requirements for ByteBuddy plugins:</h3>
 * <ul>
 *   <li>Must implement {@link net.bytebuddy.build.Plugin}</li>
 *   <li>Must have a constructor accepting a {@link File} parameter (the target directory)</li>
 *   <li>Plugin classes must be available on the {@code buildTimeInstrumentationPlugin} configuration</li>
 * </ul>
 *
 * @see BuildTimeInstrumentationExtension
 * @see InstrumentPostProcessingAction
 * @see ByteBuddyInstrumenter
 */
@SuppressWarnings('unused')
class BuildTimeInstrumentationPlugin implements Plugin<Project> {
  public static final String DEFAULT_JAVA_VERSION = 'default'
  public static final String BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION = 'buildTimeInstrumentationPlugin'
  private final Logger logger = Logging.getLogger(BuildTimeInstrumentationPlugin)

  @Override
  void apply(Project project) {
    BuildTimeInstrumentationExtension extension = project.extensions.create('buildTimeInstrumentation', BuildTimeInstrumentationExtension)
    project.configurations.register(BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION) {
      it.visible = false
      it.canBeConsumed = false
      it.canBeResolved = true
    }

    ['java', 'kotlin', 'scala', 'groovy'].each { langPluginId ->
      project.pluginManager.withPlugin(langPluginId) {
        configurePostCompilationInstrumentation(langPluginId, project, extension)
      }
    }
  }

  private void configurePostCompilationInstrumentation(String language, Project project, BuildTimeInstrumentationExtension extension) {
    project.extensions.configure(SourceSetContainer) { SourceSetContainer sourceSets ->
      // For any "main" source-set configure its compile task
      sourceSets.configureEach { SourceSet sourceSet ->
        def sourceSetName = sourceSet.name
        logger.info("[BuildTimeInstrumentationPlugin] source-set: $sourceSetName, language: $language")

        if (!sourceSetName.startsWith(SourceSet.MAIN_SOURCE_SET_NAME)) {
          logger.debug("[BuildTimeInstrumentationPlugin] Skipping non-main source set {} for language {}", sourceSetName, language)
          return
        }

        def compileTaskName = sourceSet.getCompileTaskName(language)
        logger.info("[BuildTimeInstrumentationPlugin] compile task name: " + compileTaskName)

        // For each _main_ compile task, append an instrumenting post-processing step
        // Examples of compile tasks:
        // - compileJava,
        // - compileMain_java17Java,
        // - compileMain_jetty904Java,
        // - compileMain_play25Java,
        // - compileKotlin,
        // - compileScala,
        // - compileGroovy,
        project.tasks.withType(AbstractCompile).matching {
          def sourceIsEmpty = it.source.isEmpty()
          if (sourceIsEmpty) {
            logger.debug("[BuildTimeInstrumentationPlugin] Skipping $compileTaskName for source set $sourceSetName as it has no source files")
          }
          it.name == compileTaskName && !sourceIsEmpty
        }.configureEach {
          logger.info('[BuildTimeInstrumentationPlugin] Applying buildTimeInstrumentationPlugin configuration as compile task input')
          it.inputs.files(project.configurations.named(BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION))

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
          javaVersion = javaVersion ?: DEFAULT_JAVA_VERSION // null not accepted for tasks input
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
          logger.info("[BuildTimeInstrumentationPlugin] Configured post-compile instrumentation for $compileTaskName for source-set $sourceSetName")
        }
      }
    }
  }
}
