package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import jdk.jfr.EventType;

final class ExceptionSampler {
  private static final int SAMPLING_WINDOW_DURATION_SEC = 1;
  private final StreamingSampler sampler;
  private final EventType exceptionSampleType;

  ExceptionSampler(final Config config) {
    this(
        getSamplingWindowDuration(), // fixed 1sec sampling window
        getSamplesPerWindow(config));
  }

  ExceptionSampler(final Duration windowDuration, final int samplesPerWindow) {
    sampler = new StreamingSampler(windowDuration, samplesPerWindow, 60);
    exceptionSampleType = EventType.getEventType(ExceptionSampleEvent.class);
  }

  private static Duration getSamplingWindowDuration() {
    return Duration.of(SAMPLING_WINDOW_DURATION_SEC, ChronoUnit.SECONDS);
  }

  private static int getSamplesPerWindow(Config config) {
    return (int)Math.min(config.getProfilingExceptionSamplerLimit() / Duration.of(config.getProfilingUploadPeriod(), ChronoUnit.SECONDS).dividedBy(getSamplingWindowDuration()), Integer.MAX_VALUE);
  }

  boolean sample() {
    return sampler.sample();
  }

  boolean isEnabled() {
    return exceptionSampleType.isEnabled();
  }
}
