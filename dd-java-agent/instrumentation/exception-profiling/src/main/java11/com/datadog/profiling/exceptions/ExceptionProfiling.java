package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;

/**
 * JVM-wide singleton exception profiling service. Uses {@linkplain Config} class to configure
 * itself using either system properties, environment or properties override.
 */
public final class ExceptionProfiling {

  private static ExceptionProfiling instance;

  static {
    /*
    We have to initialize this asynchronously to avoid in infinite recursion when loading of ExceptionProfiling causes
    exceptions being thrown which hits intrumentation and forces ExceptionProfiling loading again
     */
    final Thread thread =
        new Thread(
            () -> {
              instance = new ExceptionProfiling(Config.get());
            },
            "Exception sampler initialization");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Get a pre-configured shared instance.
   *
   * @return the shared instance
   */
  public static ExceptionProfiling getInstance() {
    return ExceptionProfiling.instance;
  }

  private final ExceptionHistogram histogram;
  private final ExceptionSampler sampler;

  private ExceptionProfiling(final Config config) {
    this(new ExceptionSampler(config), new ExceptionHistogram(config));
  }

  ExceptionProfiling(final ExceptionSampler sampler, final ExceptionHistogram histogram) {
    this.sampler = sampler;
    this.histogram = histogram;
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
}
