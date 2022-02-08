package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.DatadogClassLoader.BootstrapClassLoaderProxy;
import java.lang.reflect.Method;

public class Utils {

  // This is used in HelperInjectionTest.groovy
  private static Method findLoadedClassMethod = null;

  private static final BootstrapClassLoaderProxy unitTestBootstrapProxy =
      new BootstrapClassLoaderProxy();

  static {
    try {
      findLoadedClassMethod = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
    } catch (final NoSuchMethodException | SecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Return the classloader the core agent is running on. */
  public static ClassLoader getAgentClassLoader() {
    return AgentInstaller.class.getClassLoader();
  }

  /** Return a classloader which can be used to look up bootstrap resources. */
  public static ClassLoader getBootstrapProxy() {
    if (getAgentClassLoader() instanceof DatadogClassLoader) {
      return ((DatadogClassLoader) getAgentClassLoader()).getBootstrapProxy();
    } else {
      // in a unit test
      return unitTestBootstrapProxy;
    }
  }

  private Utils() {}
}
