package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;

import datadog.trace.common.sampling.PrioritySampler;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.core.CoreSpan;

public class ControllableSampler implements Sampler, PrioritySampler {
  protected int nextSamplingPriority = SAMPLER_KEEP;

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(T span) {
    span.setSamplingPriority(nextSamplingPriority, DEFAULT);
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(T span) {
    return true;
  }
}
