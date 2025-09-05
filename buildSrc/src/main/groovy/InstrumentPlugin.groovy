import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
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

          def plugins = extension.plugins
          it.inputs.property("plugins", plugins)

          def compileTaskClasspath = it.classpath
          def rawClassesDir = it.destinationDirectory

          def additionalClassPath = findAdditionalClassPath(extension)
          it.inputs.files(additionalClassPath)

          def instrumentingClassPath = project.objects.fileCollection()
          instrumentingClassPath.setFrom(
            compileTaskClasspath,
            rawClassesDir,
            additionalClassPath,
          )

          // This were the post processing happens, i.e. were the instrumentation is applied
          it.doLast(
            "instrumentClasses",
            project.objects.newInstance(
              InstrumentPostProcessingAction,
              javaVersion,
              plugins,
              instrumentingClassPath,
              rawClassesDir,
            )
          )
          logger.info("[InstrumentPlugin] Configured post-compile instrumentation for $compileTaskName for source-set $sourceSetName")
        }
      }
    }
  }

  private static List<Provider<Directory>> findAdditionalClassPath(InstrumentExtension extension) {
    return extension.additionalClasspath.getOrDefault('instrumentJava', []).collect { dirProperty ->
      dirProperty.map {
        def fileName = it.asFile.name
        it.dir("../${fileName.replaceFirst('^main', 'raw')}") // TODO there's a hidden contract here
      }
    }
  }
}

abstract class InstrumentExtension {
  abstract ListProperty<String> getPlugins()
  Map<String /* taskName */, List<DirectoryProperty>> additionalClasspath = [:]
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

  final String javaVersion
  final ListProperty<String> plugins
  final FileCollection instrumentingClassPath
  final DirectoryProperty rawClassesDirectory

  @Inject
  InstrumentPostProcessingAction(
    String javaVersion,
    ListProperty<String> plugins,
    FileCollection instrumentingClassPath,
    DirectoryProperty rawClassesDir
  ) {
    this.javaVersion = javaVersion
    this.plugins = plugins
    this.instrumentingClassPath = instrumentingClassPath
    this.rawClassesDirectory = rawClassesDir
  }

  @Override
  void execute(AbstractCompile task) {
    logger.info(
      "[InstrumentPostProcessingAction] About to instrument classes \n" +
        "  javaVersion=${javaVersion}, \n" +
        "  plugins=${plugins.get()}, \n" +
        "  instrumentingClassPath=${instrumentingClassPath.files}, \n" +
        "  rawClassesDirectory=${rawClassesDirectory.get().asFile}"
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
      parameters.classesDirectory.set(postCompileAction.rawClassesDirectory)
      parameters.tmpDirectory.set(project.layout.buildDirectory.dir("tmp/${task.name}-raw-classes"))
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
  DirectoryProperty getClassesDirectory()
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
    Path classesDirectory = parameters.classesDirectory.get().asFile.toPath()
    Path tmpSourceDir = parameters.tmpDirectory.get().asFile.toPath()

    // Delete tmpSourceDir contents recursively
    if (Files.exists(tmpSourceDir)) {
      Files.walk(tmpSourceDir)
        .sorted(Comparator.reverseOrder())
        .forEach { p ->
          if (!p.equals(tmpSourceDir)) {
            Files.deleteIfExists(p)
          }
        }
    }

    Files.move(
      classesDirectory,
      tmpSourceDir,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )

    ClassLoader instrumentingCL = createClassLoader(parameters.instrumentingClassPath, pluginCL)
    InstrumentingPlugin.instrumentClasses(plugins, instrumentingCL, tmpSourceDir.toFile(), classesDirectory.toFile())
  }

  static ClassLoader createClassLoader(cp, parent = InstrumentAction.classLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
