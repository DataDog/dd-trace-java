package datadog.trace.bootstrap;

/**
 * Enforces loading of certain packages from the bootstrap Classloader.
 *
 * <p>The idea here is to keep this class safe to inject into client's class loader.
 */
public final class BootstrapLoadedPackages {

  /**
   * packages which will be loaded on the bootstrap classloader
   *
   * <p>Updates should be mirrored in
   * datadog.trace.agent.test.SpockRunner#BOOTSTRAP_PACKAGE_PREFIXES_COPY
   *
   * <p>If a prefix not prefixed by "datadog." is added here, the short-circuit in {@code
   * datadog.trace.instrumentation.classloading.ClassLoadingInstrumentation} must be updated.
   */
  public static final String[] BOOTSTRAP_PACKAGE_PREFIXES = {
    "datadog.slf4j",
    "datadog.trace.api",
    "datadog.trace.bootstrap",
    "datadog.trace.context",
    "datadog.trace.instrumentation.api",
    "datadog.trace.logging",
    "datadog.trace.util",
  };

  public static boolean mayForceLoadWithBootstrapClassLoader(String name) {
    return name.startsWith("datadog.");
  }

  public static Class<?> forceLoadWithBootstrapClassLoaderIfNecessary(String name) {
    for (String prefix : BootstrapLoadedPackages.BOOTSTRAP_PACKAGE_PREFIXES) {
      if (name.startsWith(prefix)) {
        try {
          return Class.forName(name, false, null);
        } catch (ClassNotFoundException e) {
          // didn't work, revert to regular loading
        }
      }
    }
    return null;
  }

  private BootstrapLoadedPackages() {}
}
