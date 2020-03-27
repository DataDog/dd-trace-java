package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import jdk.jfr.EventType;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

final class ExceptionSampler {
  private final StreamingSampler sampler;
  private final EventType exceptionSampleType;

  ExceptionSampler(final Config config) {
    this(
      Duration.of(config.getProfilingExceptionSamplerSlidingWindow(), ChronoUnit.SECONDS),
      config.getProfilingExceptionSamplerSlidingWindowSamples(),
      config.getProfilingExceptionSamplerInitialInterval());
  }

  ExceptionSampler(
    final Duration windowDuration,
    final int samplesPerWindow,
    final int initialInterval) {
    sampler =
      new StreamingSampler(windowDuration, samplesPerWindow, initialInterval);
    exceptionSampleType = EventType.getEventType(ExceptionSampleEvent.class);
  }

  boolean sample() {
    return sampler.sample();
  }

  boolean isEnabled() {
    return exceptionSampleType.isEnabled();
  }
}
