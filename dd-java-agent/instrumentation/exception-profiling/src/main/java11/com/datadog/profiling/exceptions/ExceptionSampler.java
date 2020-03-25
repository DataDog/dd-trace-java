package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import jdk.jfr.EventType;

import java.util.concurrent.TimeUnit;

final class ExceptionSampler {
  private final StreamingSampler sampler;
  private final EventType exceptionSampleType;

  ExceptionSampler(final Config config) {
    this(
      config.getProfilingExceptionSamplerSlidingWindow(),
      TimeUnit.SECONDS,
      config.getProfilingExceptionSamplerSlidingWindowSamples(),
      config.getProfilingExceptionSamplerInitialInterval());
  }

  ExceptionSampler(
    final long windowDuration,
    final TimeUnit windowDurationUnit,
    final int samplesPerWindow,
    final int initialInterval) {
    sampler =
      new StreamingSampler(windowDuration, windowDurationUnit, samplesPerWindow, initialInterval);
    exceptionSampleType = EventType.getEventType(ExceptionSampleEvent.class);
  }

  boolean sample() {
    return sampler.sample();
  }

  boolean isEnabled() {
    return exceptionSampleType.isEnabled();
  }
}
