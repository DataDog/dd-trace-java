package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.DatadogClassLoader.BootstrapClassLoaderProxy;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

public class Utils {

  private static final ClassLoader bootstrapProxy =
      getAgentClassLoader() instanceof DatadogClassLoader
          ? ((DatadogClassLoader) getAgentClassLoader()).getBootstrapProxy()
          : new BootstrapClassLoaderProxy(); // only used during unit tests

  /** Return the classloader the core agent is running on. */
  public static ClassLoader getAgentClassLoader() {
    return Instrumenter.class.getClassLoader();
  }

  /** Return a classloader which can be used to look up bootstrap resources. */
  public static ClassLoader getBootstrapProxy() {
    return bootstrapProxy;
  }

  private static final AtomicReference<Instrumentation> instrumentationRef =
      new AtomicReference<>();

  public static void setInstrumentation(Instrumentation instrumentation) {
    instrumentationRef.compareAndSet(null, instrumentation);
  }

  public static Instrumentation getInstrumentation() {
    return instrumentationRef.get();
  }

  private Utils() {}
}
