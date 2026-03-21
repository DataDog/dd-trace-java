package datadog.trace.core;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.sampling.PrioritySampler;
import datadog.trace.common.sampling.Sampler;

public class ControllableSampler implements Sampler, PrioritySampler {

  public int nextSamplingPriority = (int) PrioritySampling.SAMPLER_KEEP;

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(T span) {
    span.setSamplingPriority(nextSamplingPriority, SamplingMechanism.DEFAULT);
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(T span) {
    return true;
  }
}
