package datadog.trace.common.sampling;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.SimpleRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class ServiceAsmTimeTraceSampler implements Sampler, PrioritySampler {

  private static final Logger log = LoggerFactory.getLogger(ServiceAsmTimeTraceSampler.class);

  private final SimpleRateLimiter rateLimiter;

  public ServiceAsmTimeTraceSampler() {
    this.rateLimiter = new SimpleRateLimiter(60); //one per minute
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped.
    // This allows the core agent to collect stats on all traces.
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {

    //TODO check how to short circuit this for ASM

    if (rateLimiter.tryAcquire()) {
      span.setSamplingPriority(
          PrioritySampling.SAMPLER_KEEP,
          SamplingMechanism.DEFAULT);
    } else {
      span.setSamplingPriority(
          PrioritySampling.SAMPLER_DROP,
          SamplingMechanism.DEFAULT);
    }

  }

}
