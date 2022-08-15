package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.BootstrapProxy;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

public class Utils {

  /** Return the classloader the core agent is running on. */
  public static ClassLoader getAgentClassLoader() {
    return Instrumenter.class.getClassLoader();
  }

  /** Return a classloader which can be used to look up bootstrap resources. */
  public static ClassLoader getBootstrapProxy() {
    return BootstrapProxy.INSTANCE;
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
