package datadog.gradle.plugin.instrument

import datadog.gradle.plugin.instrument.BuildTimeInstrumentationPlugin.Companion.BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

abstract class InstrumentPostProcessingAction @Inject constructor(
  javaVersion: String,
  val plugins: ListProperty<String>,
  val instrumentingClassPath: FileCollection,
  val compilerOutputDirectory: DirectoryProperty,
  val tmpDirectory: DirectoryProperty,
  val includeClassDirectories: FileCollection
) : Action<Task> {
  private val logger = Logging.getLogger(InstrumentPostProcessingAction::class.java)
  private val resolvedJavaVersion: JavaLanguageVersion = when (javaVersion) {
    BuildTimeInstrumentationPlugin.DEFAULT_JAVA_VERSION -> JavaLanguageVersion.current()
    else -> JavaLanguageVersion.of(javaVersion)
  }

  @get:Inject
  abstract val project: Project

  @get:Inject
  abstract val javaToolchainService: JavaToolchainService

  @get:Inject
  abstract val invocationDetails: BuildInvocationDetails

  @get:Inject
  abstract val workerExecutor: WorkerExecutor

  override fun execute(task: Task) {
    logger.info(
      """
      [InstrumentPostProcessingAction] About to instrument classes
        javaVersion=$resolvedJavaVersion,
        plugins=${plugins.get()},
        instrumentingClassPath=${instrumentingClassPath.files},
        rawClassesDirectory=${compilerOutputDirectory.get().asFile}
      """
        .trimIndent()
    )

    val action = this
    workQueue().submit(InstrumentAction::class) {
      buildStartedTime.set(invocationDetails.buildStartedTime)
      pluginClassPath.from(project.configurations.named(BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION))
      plugins.set(action.plugins)
      instrumentingClassPath.setFrom(action.instrumentingClassPath)
      compilerOutputDirectory.set(action.compilerOutputDirectory)
      tmpDirectory.set(action.tmpDirectory)
      includeClassDirectories.setFrom(action.includeClassDirectories)
    }
  }

  private fun workQueue(): WorkQueue {
    val javaLauncher = javaToolchainService.launcherFor {
      languageVersion.set(resolvedJavaVersion)
    }.get()
    return workerExecutor.processIsolation {
      forkOptions {
        setExecutable(javaLauncher.executablePath.asFile.absolutePath)
      }
    }
  }
}
