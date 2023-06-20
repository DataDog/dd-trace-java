package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import datadog.trace.api.Config;

/**
 * JVM-wide singleton exception profiling service. Uses {@linkplain Config} class to configure
 * itself using either system properties, environment or properties override.
 */
public final class ExceptionProfiling {

  /** Lazy initialization-on-demand. */
  private static final class Holder {
    static final ExceptionProfiling INSTANCE = new ExceptionProfiling(Config.get());
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

  public ExceptionSampleEvent process(final Throwable t, final int stackDepth) {
    // always record the exception in histogram
    final boolean firstHit = histogram.record(t);

    final boolean sampled = sampler.sample();
    if (firstHit || sampled) {
      return new ExceptionSampleEvent(t, stackDepth, sampled, firstHit);
    }
    return null;
  }

  boolean recordExceptionMessage() {
    return recordExceptionMessage;
  }
}
