package datadog.trace.common.sampling;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.DDSpan;
import lombok.extern.slf4j.Slf4j;

/** A sampler which forces the sampling priority to SAMPLER_KEEP */
@Slf4j
public class ForceKeepSampler implements Sampler, PrioritySampler {

  @Override
  public boolean sample(final DDSpan span) {
    return true;
  }

  @Override
  public void setSamplingPriority(final DDSpan span) {
    span.context().setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
  }
}
