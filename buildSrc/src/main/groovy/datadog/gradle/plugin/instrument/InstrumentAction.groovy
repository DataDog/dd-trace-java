package datadog.gradle.plugin.instrument

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.model.ObjectFactory
import org.gradle.workers.WorkAction

abstract class InstrumentAction implements WorkAction<InstrumentWorkParameters> {
  private static final Object lock = new Object()
  private static final Map<String, ClassLoader> classLoaderCache = new ConcurrentHashMap<>()
  private static volatile long lastBuildStamp

  @Inject
  public abstract FileSystemOperations getFileSystemOperations();

  @Inject
  public abstract ObjectFactory getObjects();

  @Override
  void execute() {
    String[] plugins = parameters.getPlugins().get() as String[]
    String classLoaderKey = plugins.join(':')

    // reset shared class-loaders each time a new build starts
    long buildStamp = parameters.buildStartedTime.get()
    ClassLoader pluginCL = classLoaderCache.get(classLoaderKey)
    if (lastBuildStamp < buildStamp || !pluginCL) {
      synchronized (lock) {
        pluginCL = classLoaderCache.get(classLoaderKey)
        if (lastBuildStamp < buildStamp || !pluginCL) {
          pluginCL = createClassLoader(parameters.pluginClassPath)
          classLoaderCache.put(classLoaderKey, pluginCL)
          lastBuildStamp = buildStamp
        }
      }
    }
    Path classesDirectory = parameters.compilerOutputDirectory.get().asFile.toPath()
    Path tmpUninstrumentedDir = parameters.tmpDirectory.get().asFile.toPath()

    // Original classes will be replaced by post-processed ones
    fileSystemOperations.sync {
      from(classesDirectory)
      into(tmpUninstrumentedDir)
    }
    fileSystemOperations.delete {
      delete(objects.fileTree().from(classesDirectory))
    }

    ClassLoader instrumentingCL = createClassLoader(parameters.instrumentingClassPath, pluginCL)
    ByteBuddyInstrumenter.instrumentClasses(plugins, instrumentingCL, tmpUninstrumentedDir.toFile(), classesDirectory.toFile())
  }

  static ClassLoader createClassLoader(cp, parent = InstrumentAction.classLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
