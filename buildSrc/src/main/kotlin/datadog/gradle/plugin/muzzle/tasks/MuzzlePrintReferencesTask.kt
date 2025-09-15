package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.mainSourceSet
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import java.lang.reflect.Method
import java.net.URLClassLoader
import javax.inject.Inject

abstract class MuzzlePrintReferencesTask @Inject constructor(
  private val instrumentationProject: Project
) : AbstractMuzzleTask() {
  init {
    description = "Print references created by instrumentation muzzle"
  }

  @TaskAction
  fun printMuzzle() {
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
