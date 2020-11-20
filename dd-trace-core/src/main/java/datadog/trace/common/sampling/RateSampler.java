package datadog.trace.common.sampling;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface RateSampler<T extends AgentSpan<T>> extends Sampler<T> {
  double getSampleRate();
}
