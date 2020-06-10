package com.datadog.mlt.sampler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ThreadStackAccess {
  private static volatile ThreadStackProvider threadStackProvider = new NoneThreadStackProvider();

  /**
   * Disable JMX based ThreadStack. Will flip back to the {@linkplain NoneThreadStackProvider}
   * implementation.
   */
  public static void disableJmx() {
    log.debug("Disabling JMX thread CPU time provider");
    threadStackProvider = new NoneThreadStackProvider();
  }

  /** Enable JMX based ThreadStack */
  public static void enableJmx() {
    try {
      log.debug("Enabling JMX ThreadStack provider");
      /*
       * Can not use direct class reference to JmxThreadStackProvider since on some rare JVM implementations
       * using eager class resolution that class could be resolved at the moment when ThreadStacks are requested,
       * potentially triggering j.u.l initialization which is potentially dangerous and can be done only at certain
       * point in time.
       * Using reflection should alleviate this problem - no class constant to resolve during class load. The JMX
       * ThreadStack provider will be loaded at exact moment when the reflection code is executed. Then it is up
       * to the caller to ensure that it is safe to use JMX.
       */
      threadStackProvider =
          (ThreadStackProvider)
              Class.forName("com.datadog.mlt.sampler.JmxThreadStackProvider")
                  .getField("INSTANCE")
                  .get(null);
    } catch (final ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
      log.info("Unable to initialize JMX ThreadStack provider", e);
    }
  }

  /**
   * Get the current ThreadStack provider
   *
   * @return the actual current ThreadStack provider or {@linkplain NoneThreadStackProvider} if the
   *     JMX provider is not available
   */
  public static ThreadStackProvider getCurrentThreadStackProvider() {
    return threadStackProvider;
  }
}
