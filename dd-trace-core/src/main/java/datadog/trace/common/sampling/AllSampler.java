package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;

/** Sampler that always says yes... */
public class AllSampler implements Sampler {

  @Override
  public String toString() {
    return "AllSampler { sample=true }";
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(T span) {
    return true;
  }
}
