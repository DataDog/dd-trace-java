package datadog.gradle.plugin.instrument

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * instrument<Language> task plugin which performs build-time instrumentation of classes.
 */
@Suppress("unused")
class InstrumentPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("instrument", InstrumentExtension::class.java)

    project.tasks.matching {
      it.name in listOf("compileJava", "compileScala", "compileGroovy") ||
          it.name.matches(Regex("compileMain_.+Java"))
    }.configureEach {
      val compileTask = this as AbstractCompile
      val versionRegex = Regex("compileMain_(.+)Java")
      val versionMatch = versionRegex.matchEntire(compileTask.name)

      project.afterEvaluate {
        if (!compileTask.source.isEmpty) {
          var sourceSetSuffix: String? = null
          var javaVer: String? = null

          if (versionMatch != null) {
            sourceSetSuffix = versionMatch.groupValues[1]
            if (sourceSetSuffix.matches(Regex("java\\d+"))) {
              javaVer = sourceSetSuffix.substring(4)
            }
          }

          // insert intermediate 'raw' directory for unprocessed classes
          val classesDir = compileTask.destinationDirectory.get()
          val rawClassesDir = classesDir.dir(
            "../raw${if (sourceSetSuffix != null) "_$sourceSetSuffix" else ""}/"
          )
          compileTask.destinationDirectory.set(rawClassesDir.asFile)

          // insert task between compile and jar, and before test*
          val instrumentTaskName = compileTask.name.replace("compile", "instrument")
          val instrumentTask = project.tasks.register(
            instrumentTaskName,
            InstrumentTask::class.java
          ) {
            // Task configuration
            group = "Byte Buddy"
            description = "Instruments the classes compiled by ${compileTask.name}"
            inputs.dir(compileTask.destinationDirectory)
            outputs.dir(classesDir)

            // Task inputs
            javaVersion = javaVer
            val instrumenterConfiguration = project.configurations.named("instrumentPluginClasspath")
            if (instrumenterConfiguration.isPresent) {
              pluginClassPath.from(instrumenterConfiguration.get())
            }

            plugins.set(extension.plugins)
            instrumentingClassPath.from(
              findCompileClassPath(project, name) +
                  rawClassesDir +
                  findAdditionalClassPath(extension, name)
            )
            sourceDirectory.set(rawClassesDir)

            // Task output
            targetDirectory.set(classesDir)
          }

          if (javaVer != null) {
            project.tasks.named(
              project
              .extensions
              .getByName("sourceSets")
              .let { it as org.gradle.api.tasks.SourceSetContainer }
              .getByName("main_java${javaVer}").classesTaskName) {
              dependsOn(instrumentTask)
            }
          } else {
            project.tasks.named(
              project
              .extensions
              .getByName("sourceSets")
              .let { it as org.gradle.api.tasks.SourceSetContainer }
              .getByName("main").classesTaskName) {
              dependsOn(instrumentTask)
            }
          }
        }
      }
    }
  }

  companion object {
    fun findCompileClassPath(project: Project, taskName: String): FileCollection {
      val matcher = Regex("instrument([A-Z].+)Java").matchEntire(taskName)
      val cfgName = if (matcher != null) {
        "${matcher.groupValues[1].replaceFirstChar { it.lowercase() }}CompileClasspath"
      } else {
        "compileClasspath"
      }

      return project.configurations.named(cfgName).get().filter {
        it.name != "previous-compilation-data.bin" && !it.name.endsWith(".gz")
      }
    }

    fun findAdditionalClassPath(extension: InstrumentExtension, taskName: String): List<Directory> {
      val entries = extension.additionalClasspath[taskName].orEmpty()

      return entries.map { dirProp ->
        // insert intermediate 'raw' directory for unprocessed classes
        val dir = dirProp.get()
        val fileName = dir.asFile.name
        dir.dir("../${fileName.replaceFirst(Regex("^main"), "raw")}")
      }
    }
  }
}

abstract class InstrumentExtension {
  abstract val plugins: ListProperty<String>
  val additionalClasspath: MutableMap<String /* taskName */, List<DirectoryProperty>> = mutableMapOf()
}

abstract class InstrumentTask @Inject constructor() : DefaultTask() {
  @get:Input
  @get:Optional
  var javaVersion: String? = null

  @get:InputFiles
  @get:Classpath
  abstract val pluginClassPath: ConfigurableFileCollection

  @get:Input
  abstract val plugins: ListProperty<String>

  @get:InputFiles
  @get:Classpath
  abstract val instrumentingClassPath: ConfigurableFileCollection

  @get:InputDirectory
  abstract val sourceDirectory: DirectoryProperty

  @get:OutputDirectory
  abstract val targetDirectory: DirectoryProperty

  @get:Inject
  abstract val javaToolchainService: JavaToolchainService

  @get:Inject
  abstract val invocationDetails: BuildInvocationDetails

  @get:Inject
  abstract val workerExecutor: WorkerExecutor

  @TaskAction
  fun instrument() {
    workQueue().submit(InstrumentAction::class.java) {
      buildStartedTime.set(invocationDetails.buildStartedTime)
      pluginClassPath.from(pluginClassPath)
      plugins.set(plugins)
      instrumentingClassPath.from(instrumentingClassPath)
      sourceDirectory.set(sourceDirectory)
      targetDirectory.set(targetDirectory)
    }
  }

  private fun workQueue(): WorkQueue {
    val effectiveJavaVersion = javaVersion ?: "8"
    val javaLauncher = javaToolchainService.launcherFor {
      languageVersion.set(JavaLanguageVersion.of(effectiveJavaVersion))
    }.get()

    return workerExecutor.processIsolation {
      forkOptions {
        executable = javaLauncher.executablePath.asFile.absolutePath
      }
    }
  }
}

interface InstrumentWorkParameters : WorkParameters {
  val buildStartedTime: Property<Long>
  val pluginClassPath: ConfigurableFileCollection
  val plugins: ListProperty<String>
  val instrumentingClassPath: ConfigurableFileCollection
  val sourceDirectory: DirectoryProperty
  val targetDirectory: DirectoryProperty
}

abstract class InstrumentAction : WorkAction<InstrumentWorkParameters> {
  companion object {
    private val lock = Any()
    private val classLoaderCache: MutableMap<String, ClassLoader> = ConcurrentHashMap()

    @Volatile
    private var lastBuildStamp: Long = 0

    fun createClassLoader(
      cp: ConfigurableFileCollection,
      parent: ClassLoader = InstrumentAction::class.java.classLoader
    ): ClassLoader {
      val urls = cp.files.map { it.toURI().toURL() }.toTypedArray()
      return URLClassLoader(urls, parent)
    }
  }

  override fun execute() {
    val plugins = parameters.plugins.get().toTypedArray()
    val classLoaderKey = plugins.joinToString(":")

    // reset shared class-loaders each time a new build starts
    val buildStamp = parameters.buildStartedTime.get()
    var pluginCL: ClassLoader? = classLoaderCache[classLoaderKey]
    if (lastBuildStamp < buildStamp || pluginCL == null) {
      synchronized(lock) {
        pluginCL = classLoaderCache[classLoaderKey]
        if (lastBuildStamp < buildStamp || pluginCL == null) {
          pluginCL = createClassLoader(parameters.pluginClassPath)
          classLoaderCache[classLoaderKey] = pluginCL
          lastBuildStamp = buildStamp
        }
      }
    }
    val sourceDirectory = parameters.sourceDirectory.get().asFile
    val targetDirectory = parameters.targetDirectory.get().asFile
    val instrumentingCL = createClassLoader(parameters.instrumentingClassPath, pluginCL!!)
    InstrumentingPlugin.instrumentClasses(plugins, instrumentingCL, sourceDirectory, targetDirectory)
  }
}
