package datadog.gradle.plugin.instrument

import net.bytebuddy.ClassFileVersion
import net.bytebuddy.build.EntryPoint
import net.bytebuddy.build.Plugin
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer.Suffixing
import net.bytebuddy.utility.StreamDrainer
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Performs build-time instrumentation of classes, called indirectly from InstrumentPlugin.
 * (This is the byte-buddy side of the task; InstrumentPlugin contains the Gradle pieces.)
 */
class InstrumentingPlugin {
  companion object {
    private val log = LoggerFactory.getLogger(InstrumentingPlugin::class.java)

    @Throws(Exception::class)
    fun instrumentClasses(
      plugins: Array<String>,
      instrumentingLoader: ClassLoader,
      sourceDirectory: File,
      targetDirectory: File
    ) {
      val tccl = Thread.currentThread().contextClassLoader
      try {
        Thread.currentThread().contextClassLoader = instrumentingLoader

        val factories = plugins.map { plugin ->
          try {
            @Suppress("UNCHECKED_CAST")
            val pluginClass = instrumentingLoader.loadClass(plugin) as Class<Plugin>
            val loadedPlugin = pluginClass.getConstructor(File::class.java).newInstance(targetDirectory)
            Plugin.Factory.Simple(loadedPlugin)
          } catch (t: Throwable) {
            throw IllegalStateException("Cannot resolve plugin: $plugin", t)
          }
        }

        val source = Plugin.Engine.Source.ForFolder(sourceDirectory)
        val target = Plugin.Engine.Target.ForFolder(targetDirectory)

        val engine = Plugin.Engine.Default.of(
          EntryPoint.Default.REBASE, ClassFileVersion.ofThisVm(), Suffixing.withRandomSuffix()
        )

        val summary = engine
          .with(Plugin.Engine.PoolStrategy.Default.FAST)
          .with(NonCachingClassFileLocator(instrumentingLoader))
          .with(LoggingAdapter())
          .withErrorHandlers(
            Plugin.Engine.ErrorHandler.Enforcing.ALL_TYPES_RESOLVED,
            Plugin.Engine.ErrorHandler.Enforcing.NO_LIVE_INITIALIZERS,
            DelegatingErrorHandler(Plugin.Engine.ErrorHandler.Failing.FAIL_LAST)
          )
          .with(Plugin.Engine.Dispatcher.ForSerialTransformation.Factory.INSTANCE)
          .apply(source, target, factories)

        if (summary.failed.isNotEmpty()) {
          throw IllegalStateException("Failed to transform: ${summary.failed}")
        }
      } catch (e: Throwable) {
        Thread.currentThread().contextClassLoader = tccl
        throw e
      }
    }
  }

  private class DelegatingErrorHandler(
    private val delegate: Plugin.Engine.ErrorHandler
  ) : Plugin.Engine.ErrorHandler by delegate {
    override fun onError(throwables: Map<TypeDescription, List<Throwable>>) {
      val ise = IllegalStateException(
        "Failed to transform at least one type: $throwables"
      )
      throwables.values
        .flatten()
        .forEach { ise.addSuppressed(it) }

      throw ise
    }
  }

  private class LoggingAdapter : Plugin.Engine.Listener.Adapter() {
    override fun onTransformation(typeDescription: TypeDescription, plugins: List<Plugin>) {
      log.debug("Transformed {} using {}", typeDescription, plugins)
    }

    override fun onError(typeDescription: TypeDescription, plugin: Plugin, throwable: Throwable) {
      log.warn("Failed to transform {} using {}", typeDescription, plugin, throwable)
    }
  }

  private class NonCachingClassFileLocator(
    private val loader: ClassLoader
  ) : ClassFileLocator {
    @Throws(IOException::class)
    override fun locate(name: String): ClassFileLocator.Resolution {
      val url = loader.getResource(name.replace('.', '/') + ".class")
      if (url == null) {
        return ClassFileLocator.Resolution.Illegal(name)
      }

      val uc = url.openConnection().apply {
        useCaches = false // avoid caching class-file resources in build-workers
      }

      uc.getInputStream().use { input: InputStream ->
        return ClassFileLocator.Resolution.Explicit(StreamDrainer.DEFAULT.drain(input))
      }
    }

    override fun close() {
      // no-op
    }
  }
}
