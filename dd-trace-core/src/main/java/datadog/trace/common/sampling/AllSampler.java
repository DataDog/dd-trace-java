package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;

/** Sampler that always says yes... */
public final class AllSampler<T extends CoreSpan<T>> implements Sampler<T> {

  @Override
  public String toString() {
    return "AllSampler { sample=true }";
  }

  @Override
  public boolean sample(T span) {
    return true;
  }
}
