package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A sampler which forces the sampling priority */
public final class ForcePrioritySampler<T extends CoreSpan<T>>
    implements Sampler<T>, PrioritySampler<T> {

  private static final Logger log = LoggerFactory.getLogger(ForcePrioritySampler.class);
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
