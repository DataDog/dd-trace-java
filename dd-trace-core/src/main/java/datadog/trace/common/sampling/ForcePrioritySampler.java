package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;
import lombok.extern.slf4j.Slf4j;

/** A sampler which forces the sampling priority */
@Slf4j
public class ForcePrioritySampler<T extends CoreSpan<T>> implements Sampler<T>, PrioritySampler<T> {

  private final int prioritySampling;

  public ForcePrioritySampler(final int prioritySampling) {
    this.prioritySampling = prioritySampling;
  }

  @Override
  public boolean sample(final T span) {
    return true;
  }

  @Override
  public void setSamplingPriority(final T span) {
    span.setSamplingPriority(prioritySampling);
  }
}
