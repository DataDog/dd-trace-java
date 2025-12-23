import net.bytebuddy.ClassFileVersion
import net.bytebuddy.build.EntryPoint
import net.bytebuddy.build.Plugin
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer.Suffixing
import net.bytebuddy.utility.StreamDrainer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Performs build-time instrumentation of classes, called indirectly from InstrumentPlugin.
 * (This is the byte-buddy side of the task; InstrumentPlugin contains the Gradle pieces.)
 */
class InstrumentingPlugin {
  static final Logger log = LoggerFactory.getLogger(InstrumentingPlugin.class)

  static void instrumentClasses(
    String[] plugins, ClassLoader instrumentingLoader, File sourceDirectory, File targetDirectory)
    throws Exception {

    ClassLoader tccl = Thread.currentThread().getContextClassLoader()
    try {
      Thread.currentThread().setContextClassLoader(instrumentingLoader)

      List<Plugin.Factory> factories = new ArrayList<>()
      for (String plugin : plugins) {
        try {
          Class<Plugin> pluginClass = (Class<Plugin>) instrumentingLoader.loadClass(plugin)
          Plugin loadedPlugin = pluginClass.getConstructor(File.class).newInstance(targetDirectory)
          factories.add(new Plugin.Factory.Simple(loadedPlugin))
        } catch (Throwable throwable) {
          throw new IllegalStateException("Cannot resolve plugin: " + plugin, throwable)
        }
      }

      Plugin.Engine.Source source = new Plugin.Engine.Source.ForFolder(sourceDirectory)
      Plugin.Engine.Target target = new Plugin.Engine.Target.ForFolder(targetDirectory)

      Plugin.Engine engine =
        Plugin.Engine.Default.of(
          EntryPoint.Default.REBASE, ClassFileVersion.ofThisVm(), Suffixing.withRandomSuffix())

      Plugin.Engine.Summary summary =
        engine
          .with(Plugin.Engine.PoolStrategy.Default.FAST)
          .with(new NonCachingClassFileLocator(instrumentingLoader))
          .with(new LoggingAdapter())
          .withErrorHandlers(
            Plugin.Engine.ErrorHandler.Enforcing.ALL_TYPES_RESOLVED,
            Plugin.Engine.ErrorHandler.Enforcing.NO_LIVE_INITIALIZERS,
            new Plugin.Engine.ErrorHandler() {
              @Delegate
              Plugin.Engine.ErrorHandler delegate = Plugin.Engine.ErrorHandler.Failing.FAIL_LAST

              void onError(Map<TypeDescription, List<Throwable>> throwables) {
                throw new IllegalStateException("Failed to transform at least one type: " + throwables).tap { ise ->
                  throwables.values().flatten().each {
                    ise.addSuppressed(it)
                  }
                }
              }
            }
          )
          .with(Plugin.Engine.Dispatcher.ForSerialTransformation.Factory.INSTANCE)
          .apply(source, target, factories)

      if (!summary.getFailed().isEmpty()) {
        throw new IllegalStateException("Failed to transform: " + summary.getFailed())
      }
    } catch (Throwable e) {
      Thread.currentThread().setContextClassLoader(tccl)
      throw e
    }
  }

  static class LoggingAdapter extends Plugin.Engine.Listener.Adapter {
    @Override
    void onTransformation(TypeDescription typeDescription, List<Plugin> plugins) {
      log.debug("Transformed {} using {}", typeDescription, plugins)
    }

    @Override
    void onError(TypeDescription typeDescription, Plugin plugin, Throwable throwable) {
      log.warn("Failed to transform {} using {}", typeDescription, plugin, throwable)
    }
  }

  static class NonCachingClassFileLocator implements ClassFileLocator {
    ClassLoader loader

    NonCachingClassFileLocator(ClassLoader loader) {
      this.loader = loader
    }

    @Override
    Resolution locate(String name) throws IOException {
      URL url = loader.getResource(name.replace('.', '/') + '.class')
      if (null == url) {
        return new Resolution.Illegal(name)
      }
      URLConnection uc = url.openConnection()
      uc.setUseCaches(false) // avoid caching class-file resources in build-workers
      try (InputStream is = uc.getInputStream()) {
        return new Resolution.Explicit(StreamDrainer.DEFAULT.drain(is))
      }
    }

    @Override
    void close() {}
  }
}
