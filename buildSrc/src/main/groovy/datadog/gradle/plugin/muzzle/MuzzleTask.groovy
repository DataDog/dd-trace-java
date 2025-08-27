package datadog.gradle.plugin.muzzle


import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkerExecutor

import java.lang.reflect.Method

/**
 * muzzle task plugin which runs muzzle validation against a range of dependencies.
 */

// plugin extension classes

/**
 * A pass or fail directive for a single dependency.
 */

/**
 * Muzzle extension containing all pass and fail directives.
 */

abstract class MuzzleTask extends DefaultTask {
  {
    group = 'Muzzle'
  }

  @javax.inject.Inject
  abstract JavaToolchainService getJavaToolchainService()

  @javax.inject.Inject
  abstract BuildInvocationDetails getInvocationDetails()

  @javax.inject.Inject
  abstract WorkerExecutor getWorkerExecutor()

  public void assertMuzzle(
    NamedDomainObjectProvider<Configuration> muzzleBootstrap,
    NamedDomainObjectProvider<Configuration> muzzleTooling,
    Project instrumentationProject,
    MuzzleDirective muzzleDirective = null
  ) {
    def workQueue
    String javaVersion = muzzleDirective?.javaVersion
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
    workQueue.submit(MuzzleAction.class, parameters -> {
      parameters.buildStartedTime.set(invocationDetails.buildStartedTime)
      parameters.bootstrapClassPath.setFrom(muzzleBootstrap.get())
      parameters.toolingClassPath.setFrom(muzzleTooling.get())
      parameters.instrumentationClassPath.setFrom(MuzzlePlugin.createAgentClassPath(instrumentationProject))
      parameters.testApplicationClassPath.setFrom(MuzzlePlugin.createMuzzleClassPath(instrumentationProject, name))
      if (muzzleDirective) {
        parameters.assertPass.set(muzzleDirective.assertPass)
        parameters.muzzleDirective.set(muzzleDirective.name ?: muzzleDirective.module)
      } else {
        parameters.assertPass.set(true)
      }
    })
  }

  public void printMuzzle(Project instrumentationProject) {
    FileCollection cp = instrumentationProject.sourceSets.main.runtimeClasspath
    ClassLoader cl = new URLClassLoader(cp*.toURI()*.toURL() as URL[], null as ClassLoader)
    Method printMethod = cl.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
      .getMethod('printMuzzleReferences', ClassLoader.class)
    printMethod.invoke(null, cl)
  }
}
