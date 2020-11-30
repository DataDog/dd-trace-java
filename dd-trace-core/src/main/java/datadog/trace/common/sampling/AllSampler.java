package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;

/** Sampler that always says yes... */
public class AllSampler<T extends CoreSpan<T>> extends AbstractSampler<T> {

  @Override
  public boolean doSample(final T span) {
    return true;
  }

  @Override
  public String toString() {
    return "AllSampler { sample=true }";
  }
}
