package datadog.gradle.plugin.instrument

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.model.ObjectFactory
import org.gradle.workers.WorkAction

abstract class InstrumentAction : WorkAction<InstrumentWorkParameters> {
  @get:Inject
  abstract val fileSystemOperations: FileSystemOperations

  @get:Inject
  abstract val objects: ObjectFactory

  override fun execute() {
    val plugins = parameters.plugins.get().toTypedArray()
    val classLoaderKey = plugins.joinToString(":")

    // Reset shared class-loaders each time a new build starts.
    val buildStamp = parameters.buildStartedTime.get()
    var pluginClassLoader = classLoaderCache[classLoaderKey]
    if (lastBuildStamp < buildStamp || pluginClassLoader == null) {
      synchronized(lock) {
        pluginClassLoader = classLoaderCache[classLoaderKey]
        if (lastBuildStamp < buildStamp || pluginClassLoader == null) {
          val created = createClassLoader(parameters.pluginClassPath.files)
          pluginClassLoader = created
          classLoaderCache[classLoaderKey] = created
          lastBuildStamp = buildStamp
        }
      }
    }

    val originalClassesDirectory = parameters.compilerOutputDirectory.get().asFile.toPath()
    val tmpUninstrumentedDir = parameters.tmpDirectory.get().asFile.toPath()

    // Essentially a move of the original classes to the temp directory,
    // because original classes will be replaced by post-processed ones.
    run {
      fileSystemOperations.sync {
        from(originalClassesDirectory)
        into(tmpUninstrumentedDir)
      }
      // Merge any additional class directories (e.g. unpacked dependency JARs) to be processed
      parameters.includeClassDirectories.files.forEach { classesDir ->
        if (classesDir.exists()) {
          fileSystemOperations.copy {
            from(classesDir)
            into(tmpUninstrumentedDir)
          }
        }
      }
      fileSystemOperations.delete {
        delete(objects.fileTree().from(originalClassesDirectory))
      }
    }

    val instrumentingClassLoader = createClassLoader(
      cp = parameters.instrumentingClassPath,
      parent = pluginClassLoader
    )
    ByteBuddyInstrumenter.instrumentClasses(
      plugins = plugins,
      instrumentingLoader = instrumentingClassLoader,
      sourceDirectory = tmpUninstrumentedDir.toFile(),
      targetDirectory = originalClassesDirectory.toFile()
    )
  }

  companion object {
    private val lock = Any()
    private val classLoaderCache = ConcurrentHashMap<String, ClassLoader>()
    @Volatile private var lastBuildStamp: Long = 0L

    @JvmStatic
    fun createClassLoader(
      cp: Iterable<File>,
      parent: ClassLoader? = InstrumentAction::class.java.classLoader
    ): ClassLoader {
      return URLClassLoader(cp.map { it.toURI().toURL() }.toTypedArray(), parent)
    }
  }
}
