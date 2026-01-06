package datadog.gradle.plugin.instrument

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkerExecutor

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
  final JavaLanguageVersion javaVersion
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
    this.javaVersion = javaVersion == BuildTimeInstrumentationPlugin.DEFAULT_JAVA_VERSION ? JavaLanguageVersion.current() : JavaLanguageVersion.of(javaVersion)
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
          project.configurations.named(BuildTimeInstrumentationPlugin.BUILD_TIME_INSTRUMENTATION_PLUGIN_CONFIGURATION)
      )
      parameters.plugins.set(postCompileAction.plugins)
      parameters.instrumentingClassPath.setFrom(postCompileAction.instrumentingClassPath)
      parameters.compilerOutputDirectory.set(postCompileAction.compilerOutputDirectory)
      parameters.tmpDirectory.set(postCompileAction.tmpDirectory)
    })
  }

  private workQueue() {
    def javaLauncher = this.javaToolchainService.launcherFor { spec ->
      spec.languageVersion.set(this.javaVersion)
    }.get()
    return this.workerExecutor.processIsolation { spec ->
      spec.forkOptions { fork ->
        fork.executable = javaLauncher.executablePath
      }
    }
  }
}
