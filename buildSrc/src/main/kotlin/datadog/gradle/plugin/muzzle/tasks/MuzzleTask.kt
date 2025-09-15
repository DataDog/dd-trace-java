package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.MuzzleAction
import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.allMainSourceSet
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

abstract class MuzzleTask : AbstractMuzzleTask() {
  @get:Inject
  abstract val javaToolchainService: JavaToolchainService

  @get:Inject
  abstract val invocationDetails: BuildInvocationDetails

  @get:Inject
  abstract val workerExecutor: WorkerExecutor

  fun assertMuzzle(
    muzzleBootstrap: NamedDomainObjectProvider<Configuration>,
    muzzleTooling: NamedDomainObjectProvider<Configuration>,
    instrumentationProject: Project,
    muzzleDirective: MuzzleDirective? = null
  ) {
    val workQueue = if (muzzleDirective?.javaVersion != null) {
      val javaLauncher = javaToolchainService.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(muzzleDirective.javaVersion!!))
      }.get()
      workerExecutor.processIsolation {
        forkOptions {
          executable(javaLauncher.executablePath)
        }
      }
    } else {
      workerExecutor.noIsolation()
    }
    workQueue.submit(MuzzleAction::class.java) {
      buildStartedTime.set(invocationDetails.buildStartedTime)
      bootstrapClassPath.setFrom(muzzleBootstrap.get())
      toolingClassPath.setFrom(muzzleTooling.get())
      instrumentationClassPath.setFrom(createAgentClassPath(instrumentationProject))
      testApplicationClassPath.setFrom(createMuzzleClassPath(instrumentationProject, name))
      if (muzzleDirective != null) {
        assertPass.set(muzzleDirective.assertPass)
        this.muzzleDirective.set(muzzleDirective.name ?: muzzleDirective.module)
      } else {
        assertPass.set(true)
      }
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
