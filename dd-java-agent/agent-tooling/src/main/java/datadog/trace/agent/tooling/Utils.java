package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.BootstrapProxy;
import java.lang.instrument.Instrumentation;

public class Utils {

  /** Return a classloader which can be used to look up bootstrap resources. */
  public static ClassLoader getBootstrapProxy() {
    return BootstrapProxy.INSTANCE;
  }

  /** Return the classloader the core agent is running on. */
  public static ClassLoader getAgentClassLoader() {
    return Instrumenter.class.getClassLoader();
  }

  /** Return a classloader covering the core agent plus any runtime extensions. */
  public static ClassLoader getExtendedClassLoader() {
    return Utils.extendedClassLoader;
  }

  /** Return access to the current JVM instrumentation services. */
  public static Instrumentation getInstrumentation() {
    return Utils.instrumentation;
  }

  private static volatile ClassLoader extendedClassLoader = getAgentClassLoader();

  public static void setExtendedClassLoader(ClassLoader extendedClassLoader) {
    if (getAgentClassLoader() == Utils.extendedClassLoader) {
      Utils.extendedClassLoader = extendedClassLoader;
    }
  }

  private static volatile Instrumentation instrumentation;

  public static void setInstrumentation(Instrumentation instrumentation) {
    if (null == Utils.instrumentation) {
      Utils.instrumentation = instrumentation;
    }
  }

  private Utils() {}
}
