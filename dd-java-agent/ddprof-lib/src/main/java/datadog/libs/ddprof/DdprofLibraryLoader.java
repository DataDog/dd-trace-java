package datadog.libs.ddprof;

import com.datadoghq.profiler.JVMAccess;
import com.datadoghq.profiler.JavaProfiler;
import com.datadoghq.profiler.LibraryLoader;
import com.datadoghq.profiler.OTelContext;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.TempLocationManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper around unified loading of the Datadog profiler and JVM access. It exposes {@linkplain
 * JavaProfiler} and {@linkplain JVMAccess} components which are lazily loaded. The wrapper makes
 * sure the underlying library is loaded only once. Each component is returned within a 'holder'
 * which provides the component as well as the reason why a component could not have been
 * constructed, if that's the case.
 */
public final class DdprofLibraryLoader {
  private static final Logger log = LoggerFactory.getLogger(DdprofLibraryLoader.class.getName());

  public abstract static class ComponentHolder<T> {
    private volatile boolean loaded = false;

    private T component;
    private Throwable reasonNotLoaded;

    private final Supplier<? extends ComponentHolder<T>> initializer;

    protected ComponentHolder(T component, Throwable reasonNotLoaded) {
      assert component != null || reasonNotLoaded != null;
      this.component = component;
      this.reasonNotLoaded = reasonNotLoaded;
      this.initializer = null; // no need to initialize again
      this.loaded = true; // already loaded
    }

    protected ComponentHolder(Supplier<? extends ComponentHolder<T>> initializer) {
      assert initializer != null;
      this.initializer = initializer;
    }

    private void initialize() {
      if (!loaded) {
        // all holders need to be synchronized on the same class to ensure that only one is loading
        // the native library at a time
        synchronized (DdprofLibraryLoader.class) {
          if (!loaded) {
            ComponentHolder<T> h = initializer.get();
            component = h.getComponent();
            reasonNotLoaded = h.getReasonNotLoaded();
            loaded = true;
          }
        }
      }
    }

    /**
     * Returns whether a load of this component has been attempted (successfully or not), without
     * triggering one.
     */
    public final boolean isLoadAttempted() {
      return loaded;
    }

    /** Returns whether this component was successfully loaded, without initializing it. */
    public final boolean isLoaded() {
      return loaded && component != null;
    }

    public final T getComponent() {
      initialize();
      return component;
    }

    public final Throwable getReasonNotLoaded() {
      initialize();
      return reasonNotLoaded;
    }
  }

  public static final class JavaProfilerHolder extends ComponentHolder<JavaProfiler> {
    public JavaProfilerHolder(Supplier<? extends ComponentHolder<JavaProfiler>> initializer) {
      super(initializer);
    }

    JavaProfilerHolder(JavaProfiler component, Throwable reasonNotLoaded) {
      super(component, reasonNotLoaded);
    }
  }

  public static final class JVMAccessHolder extends ComponentHolder<JVMAccess> {
    public JVMAccessHolder(Supplier<? extends ComponentHolder<JVMAccess>> initializer) {
      super(initializer);
    }

    JVMAccessHolder(JVMAccess component, Throwable reasonNotLoaded) {
      super(component, reasonNotLoaded);
    }
  }

  public static final class OTelContextHolder extends ComponentHolder<OTelContext> {
    public OTelContextHolder(Supplier<? extends ComponentHolder<OTelContext>> initializer) {
      super(initializer);
    }

    OTelContextHolder(OTelContext component, Throwable reasonNotLoaded) {
      super(component, reasonNotLoaded);
    }
  }

  /** Outcome of {@link #checkLibrary()}. */
  public static final class LibraryCheck {
    /** Whether the native library is loaded. */
    public final boolean loaded;

    /** Whether the outcome comes from an earlier load attempt rather than from this check. */
    public final boolean earlierAttempt;

    /** Why the native library is not loaded; {@code null} if it is loaded or the reason is lost. */
    public final Throwable reasonNotLoaded;

    LibraryCheck(boolean loaded, boolean earlierAttempt, Throwable reasonNotLoaded) {
      this.loaded = loaded;
      this.earlierAttempt = earlierAttempt;
      this.reasonNotLoaded = reasonNotLoaded;
    }
  }

  private static final JavaProfilerHolder PROFILER_HOLDER =
      new JavaProfilerHolder(DdprofLibraryLoader::initJavaProfiler);

  private static final JVMAccessHolder JVM_ACCESS_HOLDER =
      new JVMAccessHolder(DdprofLibraryLoader::initJVMAccess);

  private static final OTelContextHolder OTEL_CONTEXT_HOLDER =
      new OTelContextHolder(DdprofLibraryLoader::initOtelContext);

  public static JavaProfilerHolder javaProfiler() {
    return PROFILER_HOLDER;
  }

  public static JVMAccessHolder jvmAccess() {
    return JVM_ACCESS_HOLDER;
  }

  public static OTelContextHolder otelContext() {
    return OTEL_CONTEXT_HOLDER;
  }

  /**
   * Checks the native library the same way the profiler loads it, without initializing any
   * component. The check is serialized with component loading, so a load in progress is awaited and
   * its outcome reported.
   */
  public static LibraryCheck checkLibrary() {
    if (javaProfiler().isLoaded()) {
      return new LibraryCheck(true, true, null);
    }

    synchronized (DdprofLibraryLoader.class) {
      JavaProfilerHolder profiler = javaProfiler();
      if (profiler.isLoadAttempted()) {
        return new LibraryCheck(profiler.isLoaded(), true, profiler.getReasonNotLoaded());
      }
      LibraryLoader.Result result;
      try {
        ConfigProvider configProvider = ConfigProvider.getInstance();
        result =
            LibraryLoader.builder()
                .withLibraryLocation(
                    configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH))
                .withScratchDir(getScratchDir(configProvider))
                .load();
      } catch (Throwable t) {
        return new LibraryCheck(false, false, t);
      }

      if (result.succeeded || result.error != null) {
        return new LibraryCheck(result.succeeded, false, result.error);
      }

      // the loader reports the original error only once; look it up in the components which may
      // have attempted the load earlier
      Throwable reason = reasonNotLoaded(jvmAccess());
      return new LibraryCheck(
          false, true, reason != null ? reason : reasonNotLoaded(otelContext()));
    }
  }

  private static Throwable reasonNotLoaded(ComponentHolder<?> holder) {
    return holder.isLoadAttempted() ? holder.getReasonNotLoaded() : null;
  }

  private static JavaProfilerHolder initJavaProfiler() {
    JavaProfiler profiler;
    Throwable reasonNotLoaded = null;
    try {
      ConfigProvider configProvider = ConfigProvider.getInstance();
      String scratch = getScratchDir(configProvider);
      profiler =
          JavaProfiler.getInstance(
              configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH),
              scratch);
      // sanity test - force load Datadog profiler to catch it not being available early
      profiler.execute("status");
    } catch (Throwable t) {
      reasonNotLoaded = t;
      profiler = null;
    }
    return new JavaProfilerHolder(profiler, reasonNotLoaded);
  }

  private static JVMAccessHolder initJVMAccess() {
    ConfigProvider configProvider = ConfigProvider.getInstance();
    AtomicReference<Throwable> reasonNotLoaded = new AtomicReference<>();
    JVMAccess jvmAccess = null;
    try {
      String scratchDir = getScratchDir(configProvider);
      jvmAccess = new JVMAccess(null, scratchDir, reasonNotLoaded::set);
    } catch (Throwable t) {
      if (reasonNotLoaded.get() == null) {
        reasonNotLoaded.set(t);
      } else {
        // if we already have a reason, don't overwrite it
        // this can happen if the JVMAccess constructor throws an exception
        // and then the execute method throws another one
      }
      jvmAccess = null;
    }
    return new JVMAccessHolder(jvmAccess, reasonNotLoaded.get());
  }

  private static OTelContextHolder initOtelContext() {
    ConfigProvider configProvider = ConfigProvider.getInstance();
    AtomicReference<Throwable> reasonNotLoaded = new AtomicReference<>();
    OTelContext otelContext = null;
    try {
      String scratchDir = getScratchDir(configProvider);
      otelContext = new OTelContext(null, scratchDir, reasonNotLoaded::set);
    } catch (Throwable t) {
      if (reasonNotLoaded.get() == null) {
        reasonNotLoaded.set(t);
      } else {
        // if we already have a reason, don't overwrite it
        // this can happen if the OTelContext constructor throws an exception
        // and then the execute method throws another one
      }
      otelContext = null;
    }
    return new OTelContextHolder(otelContext, reasonNotLoaded.get());
  }

  private static String getScratchDir(ConfigProvider configProvider) throws IOException {
    String scratch = configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH);
    if (scratch == null) {
      Path scratchPath = TempLocationManager.getInstance().getTempDir().resolve("scratch");
      if (!Files.exists(scratchPath)) {
        Files.createDirectories(
            scratchPath,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
      }
      scratch = scratchPath.toString();
    }
    return scratch;
  }
}
