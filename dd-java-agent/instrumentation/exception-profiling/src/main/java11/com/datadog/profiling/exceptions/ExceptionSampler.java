package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import jdk.jfr.EventType;

final class ExceptionSampler {
  private final StreamingSampler sampler;
  private final EventType exceptionSampleType;

  ExceptionSampler(final Config config) {
    this(
        Duration.of(config.getProfilingExceptionSamplerSlidingWindow(), ChronoUnit.SECONDS),
        config.getProfilingExceptionSamplerSlidingWindowSamples());
  }

  ExceptionSampler(final Duration windowDuration, final int samplesPerWindow) {
    sampler = new StreamingSampler(windowDuration, samplesPerWindow);
    exceptionSampleType = EventType.getEventType(ExceptionSampleEvent.class);
  }

  boolean sample() {
    return sampler.sample();
  }

  boolean isEnabled() {
    return exceptionSampleType.isEnabled();
  }
}
