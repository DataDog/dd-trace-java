import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher

/**
 * instrument<Language> task plugin which performs build-time instrumentation of classes.
 */
@SuppressWarnings('unused')
class InstrumentPlugin implements Plugin<Project> {
  @Override
  void apply(Project project) {
    assert project.plugins.hasPlugin(JavaPlugin), "InstrumentPlugin must be applied after JavaPlugin"
    println("Applying InstrumentPlugin to ${project.name}")
    InstrumentExtension extension = project.extensions.create('instrument', InstrumentExtension)
    project.extensions.getByType(JavaPluginExtension).sourceSets.all { SourceSet sourceSet ->
      createTask(project, extension, sourceSet)
    }
  }

  static void createTask(Project project,  InstrumentExtension extension, SourceSet sourceSet) {
    String compileTaskName = sourceSet.getTaskName('compile', 'java')
    println("InstrumentPlugin: Creating task for $compileTaskName")
    boolean isMainSourceSet = compileTaskName in ['compileJava', 'compileScala', 'compileKotlin'] || compileTaskName =~ /compileMain_.+Java/
    if (!isMainSourceSet) {
      return
    }

    Matcher versionMatcher = compileTaskName =~ /compileMain_(.+)Java/
    String instrumentTaskName = compileTaskName.replace('compile', 'instrument')

    String sourceSetSuffix = null
    String javaVersion = null
    if (versionMatcher.matches()) {
      sourceSetSuffix = versionMatcher.group(1)
      if (sourceSetSuffix ==~ /java\d+/) {
        javaVersion = sourceSetSuffix[4..-1]
      }
    }

    project.tasks.named(compileTaskName, AbstractCompile).configure {
      // insert intermediate 'raw' directory for unprocessed classes
      Directory classesDir = it.destinationDirectory.get()
      Directory rawClassesDir = classesDir.dir(
        "../raw${sourceSetSuffix ? "_$sourceSetSuffix" : ''}/")
      it.destinationDirectory.set(rawClassesDir.asFile)

      ext.originalClassesDir = classesDir
      ext.rawClassesDir = rawClassesDir
    }

    def instrumentTask = project.tasks.register(instrumentTaskName, InstrumentTask) {
      AbstractCompile compileTask = project.tasks.named(compileTaskName, AbstractCompile).get()
      Directory originalClassesDir = compileTask.originalClassesDir
      Directory rawClassesDir = compileTask.rawClassesDir

      // insert task between compile and jar, and before test*

      // Task configuration
      it.group = 'Byte Buddy'
      it.description = "Instruments the classes compiled by ${compileTask.name}"
      it.inputs.dir(compileTask.destinationDirectory)
      it.outputs.dir(originalClassesDir)
      // Task inputs
      it.javaVersion = javaVersion
      def instrumenterConfiguration = project.configurations.named('instrumentPluginClasspath')
      if (instrumenterConfiguration.present) {
        it.pluginClassPath.from(instrumenterConfiguration.get())
      }
      it.plugins = extension.plugins
      it.instrumentingClassPath.from(
        findCompileClassPath(project, it.name) +
          rawClassesDir +
          findAdditionalClassPath(extension, it.name)
      )
      it.sourceDirectory = rawClassesDir
      // Task output
      it.targetDirectory = originalClassesDir
    }

    project.tasks.named(sourceSet.classesTaskName).configure {
      it.dependsOn(instrumentTask)
    }
  }

  static findCompileClassPath(Project project, String taskName) {
    def matcher = taskName =~ /instrument([A-Z].+)Java/
    def cfgName = matcher.matches() ? "${matcher.group(1).uncapitalize()}CompileClasspath" : 'compileClasspath'
    project.configurations.named(cfgName).findAll {
      it.name != 'previous-compilation-data.bin' && !it.name.endsWith('.gz')
    }
  }

  static findAdditionalClassPath(InstrumentExtension extension, String taskName) {
    extension.additionalClasspath.getOrDefault(taskName, []).collect {
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

abstract class InstrumentTask extends DefaultTask {
  @Input @Optional
  String javaVersion
  @InputFiles @Classpath
  abstract ConfigurableFileCollection getPluginClassPath()
  @Input
  ListProperty<String> plugins
  @InputFiles @Classpath
  abstract ConfigurableFileCollection getInstrumentingClassPath()
  @InputDirectory
  Directory sourceDirectory

  @OutputDirectory
  Directory targetDirectory

  @Inject
  abstract JavaToolchainService getJavaToolchainService()
  @Inject
  abstract BuildInvocationDetails getInvocationDetails()
  @Inject
  abstract WorkerExecutor getWorkerExecutor()

  @TaskAction
  instrument() {
    workQueue().submit(InstrumentAction.class, parameters -> {
      parameters.buildStartedTime.set(this.invocationDetails.buildStartedTime)
      parameters.pluginClassPath.from(this.pluginClassPath)
      parameters.plugins.set(this.plugins)
      parameters.instrumentingClassPath.setFrom(this.instrumentingClassPath)
      parameters.sourceDirectory.set(this.sourceDirectory.asFile)
      parameters.targetDirectory.set(this.targetDirectory.asFile)
    })
  }

  private workQueue() {
    if (this.javaVersion) {
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

interface InstrumentWorkParameters extends WorkParameters {
  Property<Long> getBuildStartedTime()
  ConfigurableFileCollection getPluginClassPath()
  ListProperty<String> getPlugins()
  ConfigurableFileCollection getInstrumentingClassPath()
  DirectoryProperty getSourceDirectory()
  DirectoryProperty getTargetDirectory()
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
    File sourceDirectory = parameters.getSourceDirectory().get().asFile
    File targetDirectory = parameters.getTargetDirectory().get().asFile
    ClassLoader instrumentingCL = createClassLoader(parameters.instrumentingClassPath, pluginCL)
    InstrumentingPlugin.instrumentClasses(plugins, instrumentingCL, sourceDirectory, targetDirectory)
  }

  static ClassLoader createClassLoader(cp, parent = InstrumentAction.classLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
