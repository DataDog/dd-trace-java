package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.mainSourceSet
import org.gradle.api.tasks.TaskAction
import java.lang.reflect.Method
import java.net.URLClassLoader

abstract class MuzzlePrintReferencesTask : AbstractMuzzleTask() {
  init {
    description = "Print references created by instrumentation muzzle"
  }

  @TaskAction
  fun printMuzzle() {
    val cp = project.mainSourceSet.runtimeClasspath
    val cl = URLClassLoader(cp.map { it.toURI().toURL() }.toTypedArray(), null)
    val printMethod: Method = cl.loadClass("datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin")
      .getMethod(
        "printMuzzleReferences",
        ClassLoader::class.java
      )
    printMethod.invoke(null, cl)
  }
}
