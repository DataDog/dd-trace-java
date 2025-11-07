package datadog.libs.ddprof;

import com.datadoghq.profiler.JVMAccess;
import com.datadoghq.profiler.JavaProfiler;
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
