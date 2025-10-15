package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM-wide singleton exception profiling service. Uses {@linkplain Config} class to configure
 * itself using either system properties, environment or properties override.
 */
public final class ExceptionProfiling {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionProfiling.class);

  /** Lazy initialization-on-demand. */
  private static final class Holder {
    static final ExceptionProfiling INSTANCE = create();

    private static ExceptionProfiling create() {
      try {
        return new ExceptionProfiling(Config.get());
      } catch (Throwable t) {
        LOGGER.debug("Unable to create ExceptionProfiling", t);
        return null;
      }
    }
  }

  /**
   * Support for excluding certain exception types because they are used for control flow or leak
   * detection.
   */
  public static final class Exclusion {
    public static void enter() {
      CallDepthThreadLocalMap.incrementCallDepth(Exclusion.class);
    }

    public static void exit() {
      CallDepthThreadLocalMap.decrementCallDepth(Exclusion.class);
    }

    public static boolean isEffective() {
      return CallDepthThreadLocalMap.getCallDepth(Exclusion.class) > 0;
    }
  }

  /**
   * Get a pre-configured shared instance.
   *
   * @return the shared instance
   */
  public static ExceptionProfiling getInstance() {
    return Holder.INSTANCE;
  }

  private final ExceptionHistogram histogram;
  private final ExceptionSampler sampler;
  private final boolean recordExceptionMessage;

  private ExceptionProfiling(final Config config) {
    this(
        new ExceptionSampler(config),
        new ExceptionHistogram(config),
        config.isProfilingRecordExceptionMessage());
  }

  ExceptionProfiling(
      final ExceptionSampler sampler,
      final ExceptionHistogram histogram,
      boolean recordExceptionMessage) {
    this.sampler = sampler;
    this.histogram = histogram;
    this.recordExceptionMessage = recordExceptionMessage;
  }

  public void start() {
    sampler.start();
  }

  public ExceptionSampleEvent process(final Throwable t) {
    // always record the exception in histogram
    final boolean firstHit = histogram.record(t);

    final boolean sampled = sampler.sample();
    if (firstHit || sampled) {
      return new ExceptionSampleEvent(t, sampled, firstHit);
    }
    return null;
  }

  boolean recordExceptionMessage() {
    return recordExceptionMessage;
  }
}
