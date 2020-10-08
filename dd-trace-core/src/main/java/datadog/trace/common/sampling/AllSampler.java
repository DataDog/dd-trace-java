package datadog.trace.common.sampling;

import datadog.trace.core.DDSpan;

/** Sampler that always says yes... */
public class AllSampler implements Sampler {
  @Override
  public boolean sample(DDSpan span) {
    return true;
  }
}
