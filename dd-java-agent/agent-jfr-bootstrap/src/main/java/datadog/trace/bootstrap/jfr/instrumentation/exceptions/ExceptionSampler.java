package datadog.trace.bootstrap.jfr.instrumentation.exceptions;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.jfr.AdaptiveIntervalSampler;
import jdk.jfr.EventType;

final class ExceptionSampler {
  private final AdaptiveIntervalSampler sampler;
  private final EventType exceptionSampleType;

  ExceptionSampler(Config config) {
    this(
        config.getProfilingExceptionSamplerInterval(),
        config.getProfilingExceptionSamplerTimeWindow() * 1000,
        config.getProfilingExceptionSamplerMaxSamples());
  }

  ExceptionSampler(int minInterval, long timeWindowMs, long maxSamples) {
    sampler = new AdaptiveIntervalSampler("exceptions", minInterval, timeWindowMs, maxSamples);
    exceptionSampleType = EventType.getEventType(ExceptionSampleEvent.class);
  }

  void reset() {
    sampler.reset();
  }

  boolean sample() {
    return sampler.sample();
  }

  boolean isEnabled() {
    return exceptionSampleType.isEnabled();
  }
}
