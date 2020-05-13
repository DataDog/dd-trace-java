package datadog.trace.core.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThreadStackAccess {
  private static volatile ThreadStackProvider threadStackProvider =
      NoneThreadStackProvider.INSTANCE;

  /**
   * Disable JMX based ThreadStack. Will flip back to the {@linkplain
   * NoneThreadStackProvider#INSTANCE} implementation.
   */
  public static void disableJmx() {
    log.debug("Disabling JMX thread CPU time provider");
    threadStackProvider = NoneThreadStackProvider.INSTANCE;
  }

  /** Enable JMX based ThreadStack */
  public static void enableJmx() {
    try {
      log.debug("Enabling JMX ThreadStack provider");
      /*
       * Can not use direct class reference to JmxThreadStackProvider since on some rare JVM implementations
       * using eager class resolution that class could be resolved at the moment when ThreadCpuTime is being loaded,
       * potentially triggering j.u.l initialization which is potentially dangerous and can be done only at certain
       * point in time.
       * Using reflection should alleviate this problem - no class constant to resolve during class load. The JMX
       * thread cpu time provider will be loaded at exact moment when the reflection code is executed. Then it is up
       * to the caller to ensure that it is safe to use JMX.
       */
      threadStackProvider =
          (ThreadStackProvider)
              Class.forName("datadog.trace.core.util.JmxThreadStackProvider")
                  .getField("INSTANCE")
                  .get(null);
    } catch (final ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
      log.info("Unable to initialize JMX ThreadStack provider", e);
    }
  }

  /**
   * Get the current ThreadStack provider
   *
   * @return the actual current ThreadStack provider or {@linkplain
   *     NoneThreadStackProvider#INSTANCE} if the JMX provider is not available
   */
  public static ThreadStackProvider getCurrentThreadStackProvider() {
    return threadStackProvider;
  }
}
