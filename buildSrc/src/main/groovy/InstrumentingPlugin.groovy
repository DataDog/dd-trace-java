import net.bytebuddy.ClassFileVersion
import net.bytebuddy.build.EntryPoint
import net.bytebuddy.build.Plugin
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer.Suffixing
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
          .with(ClassFileLocator.ForClassLoader.of(instrumentingLoader))
          .with(new LoggingAdapter())
          .withErrorHandlers(
            Plugin.Engine.ErrorHandler.Enforcing.ALL_TYPES_RESOLVED,
            Plugin.Engine.ErrorHandler.Enforcing.NO_LIVE_INITIALIZERS,
            Plugin.Engine.ErrorHandler.Failing.FAIL_LAST)
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
}
