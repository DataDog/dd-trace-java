package datadog.gradle.plugin.muzzle

import org.gradle.api.GradleException
import org.gradle.workers.WorkAction
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

abstract class MuzzleAction : WorkAction<MuzzleWorkParameters> {
    companion object {
        private val lock = Any()
        private var cachedBuildStamp: Long = 0
        private val cachedClassLoaders = HashMap<Pair<List<File>, List<File>>, Pair<ClassLoader, ClassLoader>>()

        /**
         * Creates a classloader for [cp] with the given [parent]. By default, the platform
         * classloader keeps JDK classes visible without exposing Gradle's bundled libraries.
         */
        fun createClassLoader(
          cp: Iterable<File>,
          parent: ClassLoader = ClassLoader.getPlatformClassLoader()
        ): ClassLoader {
            val urls = cp.map { it.toURI().toURL() }.toTypedArray()
            return URLClassLoader(urls, parent)
        }

        /**
         * Returns the boot and tool classloaders for the given class-paths, cached for the current build.
         *
         * Projects can extend the bootstrap class-path (see `muzzleBootstrap`), so classloaders are cached
         * per distinct class-path: reusing the ones built for another project would hide classes this
         * project declares, or expose classes it does not declare.
         */
        internal fun bootAndToolClassLoaders(
          buildStamp: Long,
          bootstrapClassPath: List<File>,
          toolingClassPath: List<File>
        ): Pair<ClassLoader, ClassLoader> = synchronized(lock) {
            if (cachedBuildStamp < buildStamp) {
                cachedClassLoaders.clear()
                cachedBuildStamp = buildStamp
            }
            cachedClassLoaders.getOrPut(bootstrapClassPath to toolingClassPath) {
                val bootCL = createClassLoader(bootstrapClassPath)
                bootCL to createClassLoader(toolingClassPath, bootCL)
            }
        }
    }

    override fun execute() {
        val (bootCL, toolCL) = bootAndToolClassLoaders(
          parameters.buildStartedTime.get(),
          parameters.bootstrapClassPath.files.toList(),
          parameters.toolingClassPath.files.toList()
        )
        val instCL = createClassLoader(parameters.instrumentationClassPath, toolCL)
        val testCL = createClassLoader(parameters.testApplicationClassPath, bootCL)
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
