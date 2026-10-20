package datadog.gradle.plugin.muzzle

import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.workers.WorkAction
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

abstract class MuzzleAction : WorkAction<MuzzleWorkParameters> {
    companion object {
        private val cachedBuild = AtomicReference<BuildCache>()

        private class BuildCache(val buildStamp: Long) {
            val classLoaders = ConcurrentHashMap<Pair<List<File>, List<File>>, Pair<ClassLoader, ClassLoader>>()
        }

        /**
         * Creates a classloader for [cp] with the given [parent]. By default, the platform
         * classloader keeps JDK classes visible without exposing Gradle's bundled libraries.
         */
        fun createClassLoader(
          cp: FileCollection,
          parent: ClassLoader = ClassLoader.getPlatformClassLoader()
        ): ClassLoader {
            val urls = cp.map { it.toURI().toURL() }.toTypedArray()
            return URLClassLoader(urls, parent)
        }

        internal fun classLoaders(
          buildStamp: Long,
          bootstrapClassPath: FileCollection,
          toolingClassPath: FileCollection
        ): Pair<ClassLoader, ClassLoader> {
            val classPaths = bootstrapClassPath.files.toList() to toolingClassPath.files.toList()
            // Each lookup keeps its build's map when another build replaces the cache.
            val cache = cachedBuild.updateAndGet { current ->
                if (current != null && current.buildStamp == buildStamp) current else BuildCache(buildStamp)
            }
            return cache.classLoaders.computeIfAbsent(classPaths) {
                val bootstrap = createClassLoader(bootstrapClassPath)
                bootstrap to createClassLoader(toolingClassPath, bootstrap)
            }
        }
    }

    internal open val scanPluginClassName: String
        get() = "datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin"

    override fun execute() {
        val (bootCL, toolCL) = classLoaders(
          parameters.buildStartedTime.get(),
          parameters.bootstrapClassPath,
          parameters.toolingClassPath
        )
        val instCL = createClassLoader(parameters.instrumentationClassPath, toolCL)
        val testCL = createClassLoader(parameters.testApplicationClassPath, bootCL)
        val assertPass = parameters.assertPass.get()
        val muzzleDirective = parameters.muzzleDirective.orNull
        val assertionMethod: Method = instCL.loadClass(scanPluginClassName)
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
