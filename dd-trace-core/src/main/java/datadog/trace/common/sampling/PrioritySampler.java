package datadog.trace.common.sampling;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface PrioritySampler<T extends AgentSpan<T>> {
  void setSamplingPriority(T span);
}
