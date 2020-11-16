package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;

public interface RateSampler<T extends CoreSpan<T>> extends Sampler<T> {
  double getSampleRate();
}
