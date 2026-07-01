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
 * Performs build-time instrumentation of classes, called indirectly from
 * [BuildTimeInstrumentationPlugin].
 *
 * This is the ByteBuddy side of the task; [BuildTimeInstrumentationPlugin] contains the Gradle
 * wiring and task configuration.
 */
object ByteBuddyInstrumenter {
  private val log = LoggerFactory.getLogger(ByteBuddyInstrumenter::class.java)

  @Throws(Exception::class)
  fun instrumentClasses(
    plugins: Array<String>,
    instrumentingLoader: ClassLoader,
    sourceDirectory: File,
    targetDirectory: File
  ) {
    withThreadContextClassloader(instrumentingLoader) {
      val factories = plugins.map {
        try {
          val pluginClass = instrumentingLoader.loadClass(it).asSubclass(Plugin::class.java)
          val loadedPlugin = pluginClass.getConstructor(File::class.java).newInstance(targetDirectory)
          Plugin.Factory.Simple(loadedPlugin)
        } catch (throwable: Throwable) {
          throw IllegalStateException("Cannot resolve plugin: $it", throwable)
        }
      }

      val source = Plugin.Engine.Source.ForFolder(sourceDirectory)
      val target = Plugin.Engine.Target.ForFolder(targetDirectory)
      val engine = Plugin.Engine.Default.of(
        EntryPoint.Default.REBASE,
        ClassFileVersion.ofThisVm(),
        Suffixing.withRandomSuffix()
      )

      val summary = engine
        .with(Plugin.Engine.PoolStrategy.Default.FAST)
        .with(NonCachingClassFileLocator(instrumentingLoader))
        .with(LoggingAdapter())
        .withErrorHandlers(
          Plugin.Engine.ErrorHandler.Enforcing.ALL_TYPES_RESOLVED,
          Plugin.Engine.ErrorHandler.Enforcing.NO_LIVE_INITIALIZERS,
          object : Plugin.Engine.ErrorHandler by Plugin.Engine.ErrorHandler.Failing.FAIL_LAST {
            override fun onError(throwables: MutableMap<TypeDescription, MutableList<Throwable>>) {
              throw IllegalStateException("Failed to transform at least one type: $throwables").apply {
                throwables.values.flatten().forEach(::addSuppressed)
              }
            }
          })
        .with(Plugin.Engine.Dispatcher.ForSerialTransformation.Factory.INSTANCE)
        .apply(source, target, factories)

      if (summary.failed.isNotEmpty()) {
        throw IllegalStateException("Failed to transform: ${summary.failed}")
      }
    }
  }

  private inline fun <T> withThreadContextClassloader(
    classloader: ClassLoader,
    block: () -> T
  ): T {
    val currentThread = Thread.currentThread()
    val tccl = currentThread.contextClassLoader
    try {
      currentThread.contextClassLoader = classloader
      return block()
    } finally {
      currentThread.contextClassLoader = tccl
    }
  }

  class LoggingAdapter : Plugin.Engine.Listener.Adapter() {
    override fun onTransformation(typeDescription: TypeDescription, plugins: MutableList<Plugin>) {
      log.debug("Transformed {} using {}", typeDescription, plugins)
    }

    override fun onError(typeDescription: TypeDescription, plugin: Plugin, throwable: Throwable) {
      log.warn("Failed to transform {} using {}", typeDescription, plugin, throwable)
    }
  }

  class NonCachingClassFileLocator(private val loader: ClassLoader) : ClassFileLocator {
    @Throws(IOException::class)
    override fun locate(name: String): ClassFileLocator.Resolution {
      val url = loader.getResource(name.replace('.', '/') + ".class")
        ?: return ClassFileLocator.Resolution.Illegal(name)

      return url.openConnection().run {
        useCaches = false // avoid caching class-file resources in build-workers
        getInputStream().use { input: InputStream ->
          ClassFileLocator.Resolution.Explicit(StreamDrainer.DEFAULT.drain(input))
        }
      }
    }

    override fun close() {}
  }
}
