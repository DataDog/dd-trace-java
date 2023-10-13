package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;

/** A sampler which forces the sampling priority */
public class ForcePrioritySampler implements Sampler, PrioritySampler {

  private final int prioritySampling;
  private final int samplingMechanism;

  public ForcePrioritySampler(final int prioritySampling, final int samplingMechanism) {
    this.prioritySampling = prioritySampling;
    this.samplingMechanism = samplingMechanism;
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {
    span.setSamplingPriority(prioritySampling, samplingMechanism);
  }
}
