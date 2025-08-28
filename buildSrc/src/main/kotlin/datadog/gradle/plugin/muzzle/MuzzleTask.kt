package datadog.gradle.plugin.muzzle

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.workers.WorkerExecutor
import java.lang.reflect.Method
import java.net.URLClassLoader
import javax.inject.Inject

abstract class MuzzleTask : DefaultTask() {
  init {
    group = "Muzzle"
  }

  @get:Inject
  abstract val javaToolchainService: JavaToolchainService

  @get:Inject
  abstract val invocationDetails: BuildInvocationDetails

  @get:Inject
  abstract val workerExecutor: WorkerExecutor

  @JvmOverloads
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

  fun printMuzzle(instrumentationProject: Project) {
    val cp = instrumentationProject.mainSourceSet.runtimeClasspath
    val cl = URLClassLoader(cp.map { it.toURI().toURL() }.toTypedArray(), null)
    val printMethod: Method = cl.loadClass("datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin")
      .getMethod(
        "printMuzzleReferences",
        ClassLoader::class.java
      )
    printMethod.invoke(null, cl)
  }
}

