package datadog.trace.common.sampling;

import datadog.trace.core.DDSpan;
import lombok.extern.slf4j.Slf4j;

/** A sampler which forces the sampling priority */
@Slf4j
public class ForcePrioritySampler implements Sampler, PrioritySampler {

  private final int prioritySampling;

  public ForcePrioritySampler(final int prioritySampling) {
    this.prioritySampling = prioritySampling;
  }

  @Override
  public boolean sample(final DDSpan span) {
    return true;
  }

  @Override
  public void setSamplingPriority(final DDSpan span) {
    span.context().setSamplingPriority(prioritySampling);
  }
}
