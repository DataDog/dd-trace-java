import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
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
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
/**
 * instrument<Language> task plugin which performs build-time instrumentation of classes.
 */
@SuppressWarnings('unused')
class InstrumentPlugin implements Plugin<Project> {
  public static final String DEFAULT_JAVA_VERSION = 'default'
  private final Logger logger = Logging.getLogger(InstrumentPlugin)

  @Override
  void apply(Project project) {
    InstrumentExtension extension = project.extensions.create('instrument', InstrumentExtension)

    project.pluginManager.withPlugin("java") {configurePostCompilationInstrumentation("java", project, extension) }
//    project.pluginManager.withPlugin("kotlin") {configurePostCompilationInstrumentation("kotlin", project, extension) }
//    project.pluginManager.withPlugin("scala") {configurePostCompilationInstrumentation("scala", project, extension) }
  }

  private void configurePostCompilationInstrumentation(String language, Project project, InstrumentExtension extension) {
    project.extensions.configure(SourceSetContainer) { SourceSetContainer sourceSets ->
      sourceSets.configureEach { SourceSet sourceSet ->
        def sourceSetName = sourceSet.name
        println("[InstrumentPlugin] source-set: $sourceSetName, language: $language")
        
        if (!sourceSetName.startsWith("main")) {
          logger.debug("[InstrumentPlugin] Skipping non-main source set {} for language {}", sourceSetName, language)
          return
        }

        def compileTaskName = sourceSet.getCompileTaskName(language)
        println("[InstrumentPlugin] compile task name: " + compileTaskName)
        // E.g. compileJava, compileMain_java17Java, compileMain_jetty904Java, compileMain_play25Java, compileEe8TestJava, compileLatestDepTestJava
        project.tasks.named(compileTaskName, AbstractCompile) {
          if (it.source.isEmpty()) {
            println("[InstrumentPlugin] Skipping $compileTaskName for source set $sourceSetName as it has no source files")
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

          def pluginClassPath = project.objects.fileCollection()
          def instrumentConfiguration = project.configurations.findByName('instrumentPluginClasspath')
          if (instrumentConfiguration != null) {
            pluginClassPath.from(instrumentConfiguration)
            it.inputs.files(pluginClassPath)
          }

          def rawClassesDir = it.destinationDirectory
          // TODO findCompileClassPath should is strange, look at
          //  https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/beba51ec74b0ced45808f74ef187af18ca4354d4/gradle-plugins/src/main/kotlin/io.opentelemetry.instrumentation.muzzle-generation.gradle.kts
          def compileClassPath = it.classpath
//          def compileClassPath = findCompileClassPath(project, it.name)
//          it.inputs.files(compileClassPath) // TODO is it needed, i.e. can it be extracted from this compile task
          def additionalClassPath = findAdditionalClassPath(extension, it.name)
          it.inputs.files(additionalClassPath)
          def instrumentingClassPath = project.objects.fileCollection()
          instrumentingClassPath.setFrom(
            compileClassPath,
            rawClassesDir,
            additionalClassPath,
          )

          it.doLast(
            "instrumentClasses",
            project.objects.newInstance(
              PostCompileInstrumentAction,
              javaVersion,
              plugins,
              pluginClassPath,
              instrumentingClassPath,
              rawClassesDir,
            )
          )
          println("[InstrumentPlugin] Configured post-compile instrumentation for $compileTaskName for source-set $sourceSetName")
        }
      }
    }
  }

//  @Override
//  void oldapply(Project project) {
////    project.plugins.apply(JavaBasePlugin::class.java)
////    project.pluginManager.apply(JavaPlugin) // ensures JavaPlugin has been applied
//
//    project.pluginManager.withPlugin()
//    project.plugins.withType()
//
//    InstrumentExtension extension = project.extensions.create('instrument', InstrumentExtension)
//
//
//
//
//
//    // project.getExtensions().configure(SourceSetContainer.class, sourceSets -> sourceSets.configureEach(sourceSet -> {
//    project.extensions.configure(SourceSetContainer) { SourceSetContainer sourceSets ->
//      sourceSets.configureEach { SourceSet sourceSet ->
//        // todo for each language java, scala, kotlin, groovy
//
//        ["java", "scala", "kotlin"].each {
//
//          def compileTaskName = sourceSet.getCompileTaskName(it)
//          println("[InstrumentPlugin] " + compileTaskName)
//
//          // extract java version from compile task name
//          String sourceSetSuffix = null
//          String javaVersion = null
//          Matcher versionMatcher = compileTaskName =~ /compileMain_(.+)Java/
//          if (versionMatcher.matches()) {
//            sourceSetSuffix = versionMatcher.group(1)
//            if (sourceSetSuffix ==~ /java\d+/) {
//              javaVersion = sourceSetSuffix[4..-1]
//            }
//          }
//
//          FileCollection classesDirs = sourceSet.output.classesDirs
//
//          // in prev impl: "raw${sourceSetSuffix ? "_$sourceSetSuffix" : ''}"
//          Provider<Directory> rawClassesOutputDir = project.layout.buildDirectory.dir("rawClasses/${sourceSet.name}")
//
//          project.tasks.named(compileTaskName, AbstractCompile) { AbstractCompile compilerTask ->
//            compilerTask.destinationDirectory.set(rawClassesOutputDir)
//          }
//
//          String instrumentTaskName = compileTaskName.replace('compile', 'instrument')
//          project.tasks.register(instrumentTaskName) {
//
//
//            it.inputs.dir(rawClassesOutputDir)  // new output from the compile task
//            it.outputs.dir(sourceSet.output)
//          }
//        }
//      }
//    }
//
//    project.extensions.configure(JavaPluginExtension) { JavaPluginExtension javaPluginExtension ->
//      javaPluginExtension.sourceSets.configureEach { SourceSet sourceSet ->
//        sourceSet.getTaskName('instrumentPluginClasspath')?.let { taskName ->
//          project.tasks.named(taskName).configure { task ->
//            task.group = 'Byte Buddy'
//            task.description = "Instruments the plugin classpath for ${sourceSet.name}"
//          }
//        }
//
//        javaPluginExtension.sourceSets.all { sourceSet ->
//          project.tasks.named(sourceSet.getCompileTaskName(language), AbstractCompile) {
//            it.doLast("postProcessor") {
//              // my post processing
//            }
//          }
//        }
//
//        // Create a task to instrument the plugin classpath
//        project.tasks.register("instrumentPluginClasspath", InstrumentTask) {
//          it.group = 'Byte Buddy'
//          it.description = "Instruments the plugin classpath for ${sourceSet.name}"
//          it.javaVersion = sourceSet.javaLanguageVersion?.asInt()
//          it.pluginClassPath.from(project.configurations.named('instrumentPluginClasspath'))
//          it.instrumentingClassPath.from(findCompileClassPath(project, it.name))
//          it.sourceDirectory.set(sourceSet.output.classesDirs.singleFile)
//          it.targetDirectory.set(sourceSet.output.classesDirs.singleFile)
//        }
//      }
//
//
//      // Add a configuration for the instrumenting plugin classpath
//      javaPluginExtension.registerFeature('instrumentPluginClasspath') {
//        it.usingSourceSet(javaPluginExtension.sourceSets.getByName('main'))
//        it.builtBy(project.tasks.named('instrumentPluginClasspath'))
//      }
//    }
//
////    project.extensions.getByType(JavaPluginExtension).sourceSets.all { SourceSet sourceSet ->
////      createTask(project, extension, sourceSet)
////    }
//
//    project.tasks.matching {
//      it.name in ['compileJava', 'compileScala', 'compileKotlin', 'compileGroovy'] ||
//        it.name =~ /compileMain_.+Java/
//    }.configureEach {
//      AbstractCompile compileTask = it as AbstractCompile
//      Matcher versionMatcher = it.name =~ /compileMain_(.+)Java/
//
//      project.afterEvaluate {
//        registerTask(compileTask, versionMatcher, it, extension)
//      }
//    }
//  }
//
//  private void registerTask(AbstractCompile compileTask, Matcher versionMatcher, Project project, InstrumentExtension extension) {
//    if (!compileTask.source.empty) {
//      String sourceSetSuffix = null
//      String javaVersion = null
//      if (versionMatcher.matches()) {
//        sourceSetSuffix = versionMatcher.group(1)
//        if (sourceSetSuffix ==~ /java\d+/) {
//          javaVersion = sourceSetSuffix[4..-1]
//        }
//      }
//
//      // insert intermediate 'raw' directory for unprocessed classes
//      Directory classesDir = compileTask.destinationDirectory.get()
//      Directory rawClassesDir = classesDir.dir(
//        "../raw${sourceSetSuffix ? "_$sourceSetSuffix" : ''}/")
//      compileTask.destinationDirectory.set(rawClassesDir.asFile)
//
//      // insert task between compile and jar, and before test*
//      String instrumentTaskName = compileTask.name.replace('compile', 'instrument')
//      def instrumentTask = project.tasks.register(instrumentTaskName, InstrumentTask) {
//        // Task configuration
//        it.group = 'Byte Buddy'
//        it.description = "Instruments the classes compiled by ${compileTask.name}"
//        it.inputs.dir(compileTask.destinationDirectory)
//        it.outputs.dir(classesDir)
//        // Task inputs
//        it.javaVersion = javaVersion
//        def instrumenterConfiguration = project.configurations.named('instrumentPluginClasspath')
//        if (instrumenterConfiguration.present) {
//          it.pluginClassPath.from(instrumenterConfiguration.get())
//        }
//        it.plugins = extension.plugins
//        it.instrumentingClassPath.from(
//          findCompileClassPath(project, it.name) +
//            rawClassesDir +
//            findAdditionalClassPath(extension, it.name)
//        )
//        it.sourceDirectory = rawClassesDir
//        // Task output
//        it.targetDirectory = classesDir
//      }
//      if (javaVersion) {
//        project.tasks.named(project.sourceSets."main_java${javaVersion}".classesTaskName) {
//          it.dependsOn(instrumentTask)
//        }
//      } else {
//        project.tasks.named(project.sourceSets.main.classesTaskName) {
//          it.dependsOn(instrumentTask)
//        }
//      }
//    }
//  }

//  static List<NamedDomainObjectProvider<Configuration>> findCompileClassPath(Project project, String taskName) {
//    def matcher = taskName =~ /instrument([A-Z].+)Java/
//    def cfgName = matcher.matches() ? "${matcher.group(1).uncapitalize()}CompileClasspath" : 'compileClasspath'
//    return project.configurations.named(cfgName).findAll {
//      it.name != 'previous-compilation-data.bin' && !it.name.endsWith('.gz')
//    }
//  }

  static List<Directory> findAdditionalClassPath(InstrumentExtension extension, String taskName) {
    return extension.additionalClasspath.getOrDefault(taskName, []).collect {
      // insert intermediate 'raw' directory for unprocessed classes
      def fileName = it.get().asFile.name
      it.get().dir("../${fileName.replaceFirst('^main', 'raw')}")
    }
  }
}

abstract class InstrumentExtension {
  abstract ListProperty<String> getPlugins()
  Map<String /* taskName */, List<DirectoryProperty>> additionalClasspath = [:]
}

abstract class PostCompileInstrumentAction implements Action<AbstractCompile> {
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
  final ConfigurableFileCollection pluginClassPath
  final ConfigurableFileCollection instrumentingClassPath
  final DirectoryProperty rawClassesDirectory

  @Inject
  PostCompileInstrumentAction(
    String javaVersion,
    ListProperty<String> plugins,
    ConfigurableFileCollection pluginClassPath,
    ConfigurableFileCollection instrumentingClassPath,
    DirectoryProperty rawClassesDir
  ) {
    this.javaVersion = javaVersion
    this.plugins = plugins
    this.pluginClassPath = pluginClassPath
    this.instrumentingClassPath = instrumentingClassPath
    this.rawClassesDirectory = rawClassesDir
  }

  @Override
  void execute(AbstractCompile task) {
    println(
      "values: " +
      "javaVersion=${javaVersion}, " +
      "plugins=${plugins.get()}, " +
      "pluginClassPath=${pluginClassPath.files}, " +
      "instrumentingClassPath=${instrumentingClassPath.files}, " +
      "rawClassesDirectory=${rawClassesDirectory.get().asFile}"
    )
    def postCompileAction = this
    workQueue().submit(InstrumentAction.class, parameters -> {
      parameters.buildStartedTime.set(invocationDetails.buildStartedTime)
      parameters.pluginClassPath.from(postCompileAction.pluginClassPath)
      parameters.plugins.set(postCompileAction.plugins)
      parameters.instrumentingClassPath.setFrom(postCompileAction.instrumentingClassPath)
      parameters.classesDirectory.set(postCompileAction.rawClassesDirectory)
      parameters.tmpDirectory.set(project.layout.buildDirectory.dir("tmp/${task.name}-raw-classes"))
    })
  }

  private workQueue() {
    if (this.javaVersion != InstrumentPlugin.DEFAULT_JAVA_VERSION) {
      def javaLauncher = this.javaToolchainService.launcherFor { spec ->
        spec.languageVersion.set(JavaLanguageVersion.of(this.javaVersion))
      }.get()
      return this.workerExecutor.processIsolation { spec ->
        spec.forkOptions { fork ->
          fork.executable = javaLauncher.executablePath
        }
      }
    } else {
      return this.workerExecutor.noIsolation()
    }
  }
}


//abstract class InstrumentTask extends DefaultTask {
//  @Input @Optional
//  String javaVersion
//  @InputFiles @Classpath
//  abstract ConfigurableFileCollection getPluginClassPath()
//  @Input
//  ListProperty<String> plugins
//  @InputFiles @Classpath
//  abstract ConfigurableFileCollection getInstrumentingClassPath()
//  @InputDirectory
//  Directory sourceDirectory
//
//  @OutputDirectory
//  Directory targetDirectory
//
//  @Inject
//  abstract JavaToolchainService getJavaToolchainService()
//  @Inject
//  abstract BuildInvocationDetails getInvocationDetails()
//  @Inject
//  abstract WorkerExecutor getWorkerExecutor()
//
//  @TaskAction
//  instrument() {
//    workQueue().submit(InstrumentAction.class, parameters -> {
//      parameters.buildStartedTime.set(this.invocationDetails.buildStartedTime)
//      parameters.pluginClassPath.from(this.pluginClassPath)
//      parameters.plugins.set(this.plugins)
//      parameters.instrumentingClassPath.setFrom(this.instrumentingClassPath)
//      parameters.sourceDirectory.set(this.sourceDirectory.asFile)
//      parameters.targetDirectory.set(this.targetDirectory.asFile)
//    })
//  }
//
//  private workQueue() {
//    if (this.javaVersion) {
//      def javaLauncher = this.javaToolchainService.launcherFor { spec ->
//        spec.languageVersion.set(JavaLanguageVersion.of(this.javaVersion))
//      }.get()
//      return this.workerExecutor.processIsolation { spec ->
//        spec.forkOptions { fork ->
//          fork.executable = javaLauncher.executablePath
//        }
//      }
//    } else {
//      return this.workerExecutor.noIsolation()
//    }
//  }
//}

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
    File classesDirectory = parameters.classesDirectory.get().asFile
    File tmpSourceDir = parameters.tmpDirectory.get().asFile
    Files.move(
      classesDirectory.toPath(),
      tmpSourceDir.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )

    ClassLoader instrumentingCL = createClassLoader(parameters.instrumentingClassPath, pluginCL)
    InstrumentingPlugin.instrumentClasses(plugins, instrumentingCL, tmpSourceDir, classesDirectory)
  }

  static ClassLoader createClassLoader(cp, parent = InstrumentAction.classLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
