package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM-wide singleton exception profiling service. Uses {@linkplain Config} class to configure
 * itself using either system properties, environment or properties override.
 */
public final class ExceptionProfiling {
  private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionProfiling.class);

  private static final Config config = Config.get();

  private static final class Singleton {
    static final ExceptionProfiling INSTANCE = new ExceptionProfiling();
  }

  /**
   * Get a pre-configured shared instance.
   *
   * @return the shared instance
   */
  public static ExceptionProfiling getInstance() {
    return ExceptionProfiling.Singleton.INSTANCE;
  }

  private final ExceptionHistogram histogram;
  private final ExceptionSampler sampler;

  private ExceptionProfiling() {
    this(new ExceptionSampler(config), new ExceptionHistogram(config));
  }

  ExceptionProfiling(final ExceptionSampler sampler, final ExceptionHistogram histogram) {
    this.sampler = sampler;
    this.histogram = histogram;
  }

  public ExceptionSampleEvent process(final Exception e) {
    // always record the exception in histogram
    final boolean firstHit = histogram.record(e);

    if (sampler.isEnabled()) {
      /*
       * If the histogram hasn't contained that particular exception type up till now then 'firstHit' == true
       * and the sample event should be emitted regardless of the sampling result.
       * We need a non-short-circuiting OR such that 'sampler.sample()' is called regardless of value
       * of 'firstHit'.
       */
      if (firstHit | sampler.sample()) {
        return new ExceptionSampleEvent(e);
      }
    }
    return null;
  }
}
