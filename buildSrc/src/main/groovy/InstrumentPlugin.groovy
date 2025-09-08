import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
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
        def compileTasks = project.tasks.withType(AbstractCompile).matching {
          it.name == compileTaskName && !it.source.isEmpty()
        }

        project.configurations.whenObjectAdded { pluginConfig ->
          if (pluginConfig.name == INSTRUMENT_PLUGIN_CLASSPATH_CONFIGURATION) {
            logger.info('[InstrumentPlugin] instrumentPluginClasspath configuration was created')
            compileTasks.configureEach {
              it.inputs.files(pluginConfig)
            }
          }
        }

        compileTasks.configureEach {
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

abstract class InstrumentExtension {
  abstract ListProperty<String> getPlugins()
  abstract ListProperty<DirectoryProperty> getAdditionalClasspath()
}

abstract class InstrumentPostProcessingAction implements Action<AbstractCompile> {
  private final Logger logger = Logging.getLogger(InstrumentPostProcessingAction)

  @Inject
  abstract Project getProject()

  @Inject
  abstract JavaToolchainService getJavaToolchainService()

  @Inject
  abstract BuildInvocationDetails getInvocationDetails()

  @Inject
  abstract WorkerExecutor getWorkerExecutor()

  // Those cannot be private other wise Groovy will fail at runtime with a missing property ex
  final String javaVersion
  final ListProperty<String> plugins
  final FileCollection instrumentingClassPath
  final DirectoryProperty compilerOutputDirectory
  final DirectoryProperty tmpDirectory

  @Inject
  InstrumentPostProcessingAction(
    String javaVersion,
    ListProperty<String> plugins,
    FileCollection instrumentingClassPath,
    DirectoryProperty compilerOutputDirectory,
    DirectoryProperty tmpDirectory
  ) {
    this.javaVersion = javaVersion
    this.plugins = plugins
    this.instrumentingClassPath = instrumentingClassPath
    this.compilerOutputDirectory = compilerOutputDirectory
    this.tmpDirectory = tmpDirectory
  }

  @Override
  void execute(AbstractCompile task) {
    logger.info(
      "[InstrumentPostProcessingAction] About to instrument classes \n" +
        "  javaVersion=${javaVersion}, \n" +
        "  plugins=${plugins.get()}, \n" +
        "  instrumentingClassPath=${instrumentingClassPath.files}, \n" +
        "  rawClassesDirectory=${compilerOutputDirectory.get().asFile}"
    )
    def postCompileAction = this
    workQueue().submit(InstrumentAction.class, parameters -> {
      parameters.buildStartedTime.set(invocationDetails.buildStartedTime)
      parameters.pluginClassPath.from(
        project.configurations.findByName(InstrumentPlugin.INSTRUMENT_PLUGIN_CLASSPATH_CONFIGURATION)
          ?: project.files()
      )
      parameters.plugins.set(postCompileAction.plugins)
      parameters.instrumentingClassPath.setFrom(postCompileAction.instrumentingClassPath)
      parameters.compilerOutputDirectory.set(postCompileAction.compilerOutputDirectory)
      parameters.tmpDirectory.set(postCompileAction.tmpDirectory)
    })
  }

  private workQueue() {
    if (!this.javaVersion != InstrumentPlugin.DEFAULT_JAVA_VERSION) {
      this.javaVersion = "8"
    }
    def javaLauncher = this.javaToolchainService.launcherFor { spec ->
      spec.languageVersion.set(JavaLanguageVersion.of(this.javaVersion))
    }.get()
    return this.workerExecutor.processIsolation { spec ->
      spec.forkOptions { fork ->
        fork.executable = javaLauncher.executablePath
      }
    }
  }
}

interface InstrumentWorkParameters extends WorkParameters {
  Property<Long> getBuildStartedTime()
  ConfigurableFileCollection getPluginClassPath()
  ListProperty<String> getPlugins()
  ConfigurableFileCollection getInstrumentingClassPath()
  DirectoryProperty getCompilerOutputDirectory()
  DirectoryProperty getTmpDirectory()
}

abstract class InstrumentAction implements WorkAction<InstrumentWorkParameters> {
  private static final Object lock = new Object()
  private static final Map<String, ClassLoader> classLoaderCache = new ConcurrentHashMap<>()
  private static volatile long lastBuildStamp

  @Override
  void execute() {
    String[] plugins = parameters.getPlugins().get() as String[]
    String classLoaderKey = plugins.join(':')

    // reset shared class-loaders each time a new build starts
    long buildStamp = parameters.buildStartedTime.get()
    ClassLoader pluginCL = classLoaderCache.get(classLoaderKey)
    if (lastBuildStamp < buildStamp || !pluginCL) {
      synchronized (lock) {
        pluginCL = classLoaderCache.get(classLoaderKey)
        if (lastBuildStamp < buildStamp || !pluginCL) {
          pluginCL = createClassLoader(parameters.pluginClassPath)
          classLoaderCache.put(classLoaderKey, pluginCL)
          lastBuildStamp = buildStamp
        }
      }
    }
    Path classesDirectory = parameters.compilerOutputDirectory.get().asFile.toPath()
    Path tmpUninstrumentedDir = parameters.tmpDirectory.get().asFile.toPath()

    // Delete previous tmpSourceDir contents recursively
    if (Files.exists(tmpUninstrumentedDir)) {
      Files.walk(tmpUninstrumentedDir)
        .sorted(Comparator.reverseOrder())
        .forEach { p ->
          if (!p.equals(tmpUninstrumentedDir)) {
            Files.deleteIfExists(p)
          }
        }
    }

    Files.move(
      classesDirectory,
      tmpUninstrumentedDir,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )

    ClassLoader instrumentingCL = createClassLoader(parameters.instrumentingClassPath, pluginCL)
    InstrumentingPlugin.instrumentClasses(plugins, instrumentingCL, tmpUninstrumentedDir.toFile(), classesDirectory.toFile())
  }

  static ClassLoader createClassLoader(cp, parent = InstrumentAction.classLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
