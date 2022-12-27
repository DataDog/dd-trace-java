package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;

public interface PrioritySampler {
  <T extends CoreSpan<T>> void setSamplingPriority(T span);
}
