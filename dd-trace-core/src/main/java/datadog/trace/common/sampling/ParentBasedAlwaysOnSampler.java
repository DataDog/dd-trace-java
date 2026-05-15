package datadog.trace.common.sampling;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;

/**
 * Implements the OpenTelemetry {@code parentbased_always_on} sampler.
 *
 * <p>Root spans are always sampled. Child spans inherit the sampling decision from their parent,
 * which is handled by the context propagation layer.
 */
public class ParentBasedAlwaysOnSampler implements Sampler, PrioritySampler {

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.DEFAULT);
  }
}
