package datadog.gradle.plugin.muzzle

import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.workers.WorkAction
import java.lang.reflect.Method
import java.net.URLClassLoader

abstract class MuzzleAction : WorkAction<MuzzleWorkParameters> {
    companion object {
        private val lock = Any()
        @Volatile
        private var lastBootCL: ClassLoader? = null
        @Volatile
        private var lastToolCL: ClassLoader? = null
        @Volatile
        private var lastBuildStamp: Long = 0
        @Volatile
        private var lastBuildPathCount: Int = 0

        fun createClassLoader(cp: FileCollection, parent: ClassLoader = ClassLoader.getSystemClassLoader()): ClassLoader {
            val urls = cp.map { it.toURI().toURL() }.toTypedArray()
            return URLClassLoader(urls, parent)
        }
    }

    override fun execute() {
        val buildStamp = parameters.buildStartedTime.get()
        val buildPathCount = parameters.bootstrapClassPath.count() + parameters.toolingClassPath.count()
        var bootCL : ClassLoader? = lastBootCL
        var toolCL : ClassLoader? = lastToolCL
        // cache boot and tool classloaders for each run; rebuild if either class-path is extended mid-build
        if (bootCL == null || toolCL == null || lastBuildStamp < buildStamp || lastBuildPathCount < buildPathCount) {
            synchronized(lock) {
                bootCL = lastBootCL
                toolCL = lastToolCL
                if (bootCL == null || toolCL == null || lastBuildStamp < buildStamp || lastBuildPathCount < buildPathCount) {
                    bootCL = createClassLoader(parameters.bootstrapClassPath)
                    toolCL = createClassLoader(parameters.toolingClassPath, bootCL!!)
                    lastBootCL = bootCL
                    lastToolCL = toolCL
                    lastBuildStamp = buildStamp
                    lastBuildPathCount = buildPathCount
                }
            }
        }
        val instCL = createClassLoader(parameters.instrumentationClassPath, toolCL!!)
        val testCL = createClassLoader(parameters.testApplicationClassPath, bootCL!!)
        val assertPass = parameters.assertPass.get()
        val muzzleDirective = parameters.muzzleDirective.orNull
        val assertionMethod: Method = instCL.loadClass("datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin")
            .getMethod(
              "assertInstrumentationMuzzled",
              ClassLoader::class.java,
              ClassLoader::class.java,
              Boolean::class.java,
              String::class.java
            )
      try {
        assertionMethod.invoke(null, instCL, testCL, assertPass, muzzleDirective)
        parameters.resultFile.get().asFile.writeText("PASSING")
      } catch (e: Exception) {
        parameters.resultFile.get().asFile.writeText(e.stackTraceToString())
        throw GradleException("Muzzle validation failed", e)
      }
    }
}
