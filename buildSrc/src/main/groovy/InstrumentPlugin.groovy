import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

import java.util.regex.Matcher

/**
 * instrument<Language> task plugin which performs build-time instrumentation of classes.
 */
class InstrumentPlugin implements Plugin<Project> {

  @Override
  void apply(Project project) {
    InstrumentExtension extension = project.extensions.create('instrument', InstrumentExtension)

    project.tasks.matching {
      it.name in ['compileJava', 'compileScala', 'compileKotlin'] ||
        it.name =~ /compileMain_(?:.+)Java/
    }.all {
      AbstractCompile compileTask = it as AbstractCompile
      Matcher versionMatcher = it.name =~ /compileMain_(.+)Java/
      project.afterEvaluate {
        if (!compileTask.source.empty) {
          String sourceSetSuffix
          String javaVersion
          if (versionMatcher.matches()) {
            sourceSetSuffix = versionMatcher.group(1)
            if (sourceSetSuffix ==~ /java\d+/) {
              javaVersion = sourceSetSuffix[4..-1]
            }
          }

          // insert intermediate 'raw' directory for unprocessed classes
          Directory classesDir = compileTask.destinationDirectory.get()
          Directory rawClassesDir = classesDir.dir(
            "../raw${sourceSetSuffix ? "_$sourceSetSuffix" : ''}/")
          compileTask.destinationDirectory.set(rawClassesDir.asFile)

          // insert task between compile and jar, and before test*
          String instrumentTaskName = compileTask.name.replace('compile', 'instrument')
          def instrumentTask = project.task(['type': InstrumentTask], instrumentTaskName) {
            description = "Instruments the classes compiled by ${compileTask.name}"
            doLast {
              instrument(javaVersion, project, extension, rawClassesDir, classesDir, it)
            }
          }
          instrumentTask.inputs.dir(compileTask.destinationDirectory)
          instrumentTask.outputs.dir(classesDir)
          if (javaVersion) {
            project.tasks.named(project.sourceSets."main_java${javaVersion}".classesTaskName) {
              it.dependsOn(instrumentTask)
            }
          } else {
            project.tasks.named(project.sourceSets.main.classesTaskName) {
              it.dependsOn(instrumentTask)
            }
          }
        }
      }
    }
  }
}

abstract class InstrumentExtension {
  abstract ListProperty<String> getPlugins()
  Map<String /* taskName */, List<DirectoryProperty>> additionalClasspath = [:]
}

abstract class InstrumentTask extends DefaultTask {
  {
    group = 'Byte Buddy'
  }

  @javax.inject.Inject
  abstract JavaToolchainService getJavaToolchainService()

  @javax.inject.Inject
  abstract BuildInvocationDetails getInvocationDetails()

  @javax.inject.Inject
  abstract WorkerExecutor getWorkerExecutor()

  void instrument(String javaVersion,
                  Project project,
                  InstrumentExtension extension,
                  Directory sourceDirectory,
                  Directory targetDirectory,
                  InstrumentTask instrumentTask)
  {
    def workQueue
    if (javaVersion) {
      def javaLauncher = javaToolchainService.launcherFor { spec ->
        spec.languageVersion.set(JavaLanguageVersion.of(javaVersion))
      }.get()
      workQueue = workerExecutor.processIsolation { spec ->
        spec.forkOptions { fork ->
          fork.executable = javaLauncher.executablePath
        }
      }
    } else {
      workQueue = workerExecutor.noIsolation()
    }
    workQueue.submit(InstrumentAction.class, parameters -> {
      parameters.buildStartedTime.set(invocationDetails.buildStartedTime)
      parameters.pluginClassPath.setFrom(project.configurations.findByName('instrumentPluginClasspath') ?: [])
      parameters.plugins.set(extension.plugins)
      parameters.instrumentingClassPath.setFrom(project.configurations.compileClasspath.findAll {
        it.name != 'previous-compilation-data.bin' && !it.name.endsWith(".gz")
      } + sourceDirectory + (extension.additionalClasspath[instrumentTask.name] ?: [])*.get())
      parameters.sourceDirectory.set(sourceDirectory.asFile)
      parameters.targetDirectory.set(targetDirectory.asFile)
    })
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
  private static ClassLoader pluginCL
  private static volatile long lastBuildStamp

  @Override
  void execute() {
    // reset shared class-loaders each time a new build starts
    long buildStamp = parameters.buildStartedTime.get()
    if (lastBuildStamp < buildStamp || !pluginCL) {
      synchronized (lock) {
        if (lastBuildStamp < buildStamp || !pluginCL) {
          pluginCL = createClassLoader(parameters.pluginClassPath)
          lastBuildStamp = buildStamp
        }
      }
    }
    String[] plugins = parameters.getPlugins().get() as String[]
    File sourceDirectory = parameters.getSourceDirectory().get().asFile
    File targetDirectory = parameters.getTargetDirectory().get().asFile
    ClassLoader instrumentingCL = createClassLoader(parameters.instrumentingClassPath, pluginCL)
    InstrumentingPlugin.instrumentClasses(plugins, instrumentingCL, sourceDirectory, targetDirectory)
  }

  static ClassLoader createClassLoader(cp, parent = InstrumentAction.classLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
