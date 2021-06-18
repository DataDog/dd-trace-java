package datadog.trace.core.util;

import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SystemAccess {
  private static final Logger log = LoggerFactory.getLogger(SystemAccess.class);
  private static volatile SystemAccessProvider systemAccessProvider = SystemAccessProvider.NONE;

  /**
   * Disable JMX based system access. Will flip back to the {@linkplain SystemAccessProvider#NONE}
   * implementation.
   */
  public static void disableJmx() {
    log.debug("Disabling JMX system access provider");
    systemAccessProvider = SystemAccessProvider.NONE;
  }

  /** Enable JMX accesses */
  public static void enableJmx() {
    if (!Config.get().isProfilingEnabled() && !Config.get().isHealthMetricsEnabled()) {
      log.debug("Will not enable JMX access. Profiling and metrics are both disabled.");
      return;
    }
    try {
      log.debug("Enabling JMX system provider");
      /*
       * Can not use direct class reference to JmxSystemProvider since on some rare JVM implementations
       * using eager class resolution that class could be resolved at the moment when methods are being loaded,
       * potentially triggering j.u.l initialization which is potentially dangerous and can be done only at certain
       * point in time.
       * Using reflection should alleviate this problem - no class constant to resolve during class load. The JMX
       * system provider will be loaded at exact moment when the reflection code is executed. Then it is up
       * to the caller to ensure that it is safe to use JMX.
       */
      systemAccessProvider =
          (SystemAccessProvider)
              Class.forName(
                      "datadog.trace.core.util.JmxSystemAccessProvider",
                      false,
                      SystemAccess.class.getClassLoader())
                  .getField("INSTANCE")
                  .get(null);
    } catch (final ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
      log.info("Unable to initialize JMX system provider", e);
    }
  }

  /**
   * Get the current thread CPU time
   *
   * @return the actual current thread CPU time or {@linkplain Long#MIN_VALUE} if the JMX provider
   *     is not available
   */
  public static long getCurrentThreadCpuTime() {
    return systemAccessProvider.getThreadCpuTime();
  }
}
