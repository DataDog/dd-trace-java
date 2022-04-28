package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.DatadogClassLoader.BootstrapClassLoaderProxy;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

public class Utils {

  /** Initialization-on-demand, only used during unit tests. */
  static class BootstrapClassLoaderHolder {
    static final ClassLoader unitTestBootstrapProxy = new BootstrapClassLoaderProxy();
  }

  /** Return the classloader the core agent is running on. */
  public static ClassLoader getAgentClassLoader() {
    return Instrumenter.class.getClassLoader();
  }

  /** Return a classloader which can be used to look up bootstrap resources. */
  public static ClassLoader getBootstrapProxy() {
    if (getAgentClassLoader() instanceof DatadogClassLoader) {
      return ((DatadogClassLoader) getAgentClassLoader()).getBootstrapProxy();
    } else {
      // only used during unit tests
      return BootstrapClassLoaderHolder.unitTestBootstrapProxy;
    }
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
