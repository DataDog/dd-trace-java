package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/**
 * JVM-wide singleton exception profiling service. Uses {@linkplain Config} class to configure itself using either system properties, environment or
 * properties override.
 */
public final class ExceptionProfiling {
  private static volatile Config config = null;

  private static final class Singleton {
    static final ExceptionProfiling INSTANCE = new ExceptionProfiling();
  }

  /**
   * Initialization routine. !!! MUST BE CALLED BEFORE {@linkplain ExceptionProfiling#getInstance()}
   * - otherwise that call will crash
   *
   * @param config the system configuration
   */
  public static void init(final Config config) {
    ExceptionProfiling.config = config;
  }

  /**
   * Get a pre-configured shared instance. !!! BEFORE FIRST CALL OF THIS METHOD {@linkplain
   * ExceptionProfiling#init(Config)} MUST HAVE BEEN INVOKED
   *
   * @return the shared instance
   * @throws NullPointerException if {@linkplain ExceptionProfiling#init(Config)} has not been
   *                              called yet
   */
  public static ExceptionProfiling getInstance() {
    assert config != null;
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

    FlightRecorder.addListener(
      new FlightRecorderListener() {
        @Override
        public void recordingStateChanged(final Recording recording) {
          if (recording.getState() == RecordingState.STOPPED) {
            sampler.reset();
          }
        }
      });
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
