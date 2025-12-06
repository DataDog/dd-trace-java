package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.MuzzleAction
import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleExtension
import datadog.gradle.plugin.muzzle.allMainSourceSet
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.property
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

@CacheableTask
abstract class MuzzleTask @Inject constructor(
  objects: ObjectFactory,
  providers: ProviderFactory,
) : AbstractMuzzleTask() {
  override fun getDescription(): String = if (muzzleDirective.isPresent) {
    "Run instrumentation muzzle on ${muzzleDirective.get().name} dependency"
  } else {
    "Run instrumentation muzzle on compile time dependencies"
  }

  @get:Inject
  abstract val javaToolchainService: JavaToolchainService

  @get:Inject
  abstract val invocationDetails: BuildInvocationDetails

  @get:Inject
  abstract val workerExecutor: WorkerExecutor

  @get:InputFiles
  @get:Classpath
  abstract val muzzleBootstrap: Property<Configuration>

  @get:InputFiles
  @get:Classpath
  abstract val muzzleTooling: Property<Configuration>

  @get:InputFiles
  @get:Classpath
  protected val agentClassPath = providers.provider { createAgentClassPath(project) }

  @get:InputFiles
  @get:Classpath
  protected val muzzleClassPath = providers.provider { createMuzzleClassPath(project, name) }

  @get:Input
  @get:Optional
  val muzzleDirective: Property<MuzzleDirective> = objects.property()

  // This output is only used to make the task cacheable, this is not exposed
  @get:OutputFile
  @get:Optional
  protected val result: RegularFileProperty = objects.fileProperty().convention(
    project.layout.buildDirectory.file("reports/$name.txt")
  )

  @TaskAction
  fun muzzle() {
    when {
      !project.extensions.getByType<MuzzleExtension>().directives.any { it.assertPass } -> {
        project.logger.info("No muzzle pass directives configured. Asserting pass against instrumentation compile-time dependencies")
        assertMuzzle()
      }

      muzzleDirective.isPresent -> {
        assertMuzzle(muzzleDirective.get())
      }
    }
  }

  private fun assertMuzzle(muzzleDirective: MuzzleDirective? = null) {
    val workQueue = if (muzzleDirective?.javaVersion != null) {
      val javaLauncher = javaToolchainService.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(muzzleDirective.javaVersion!!))
      }.get()
      // Note process isolation leaks gradle dependencies to the child process
      // and may need additional code on muzzle plugin to filter those out
      // See https://github.com/gradle/gradle/issues/33987
      workerExecutor.processIsolation {
        forkOptions {
          executable(javaLauncher.executablePath)
        }
      }
    } else {
      // noIsolation worker is OK for muzzle tasks as their checks will inspect classes outline
      // and should not be impacted by the actual running JDK.
      workerExecutor.noIsolation()
    }
    workQueue.submit(MuzzleAction::class.java) {
      buildStartedTime.set(invocationDetails.buildStartedTime)
      bootstrapClassPath.setFrom(muzzleBootstrap)
      toolingClassPath.setFrom(muzzleTooling)
      instrumentationClassPath.setFrom(agentClassPath.get())
      testApplicationClassPath.setFrom(muzzleClassPath.get())
      if (muzzleDirective != null) {
        assertPass.set(muzzleDirective.assertPass)
        this.muzzleDirective.set(muzzleDirective.name ?: muzzleDirective.module)
      } else {
        assertPass.set(true)
      }
      resultFile.set(result)
    }
  }

  private fun createAgentClassPath(project: Project): FileCollection {
    project.logger.info("Creating agent classpath for $project")
    val cp = project.files()
    cp.from(project.allMainSourceSet.map { it.runtimeClasspath })

    if (project.logger.isInfoEnabled) {
      cp.forEach { project.logger.info("-- $it") }
    }
    return cp
  }

  private fun createMuzzleClassPath(project: Project, muzzleTaskName: String): FileCollection {
    project.logger.info("Creating muzzle classpath for $muzzleTaskName")
    val cp = project.files()
    val config = if (muzzleTaskName == "muzzle") {
      project.configurations.named("compileClasspath").get()
    } else {
      project.configurations.named(muzzleTaskName).get()
    }
    cp.from(config)
    if (project.logger.isInfoEnabled) {
      cp.forEach { project.logger.info("-- $it") }
    }
    return cp
  }
}
