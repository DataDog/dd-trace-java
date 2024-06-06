package datadog.trace.common.sampling;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sampler that samples traces based on a fixed time rate in milliseconds. */
public class AsmStandaloneSampler implements Sampler, PrioritySampler {

  private static final Logger log = LoggerFactory.getLogger(AsmStandaloneSampler.class);

  private final AtomicLong lastSampleTime;
  private final int rateInMilliseconds;

  public AsmStandaloneSampler(int rateInMilliseconds) {
    this.rateInMilliseconds = rateInMilliseconds;
    this.lastSampleTime = new AtomicLong(-1);
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped.
    // This allows the core agent to collect stats on all traces.
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {

    if (shouldSample()) {
      log.debug("Set SAMPLER_KEEP for span {}", span.getSpanId());
      span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.APPSEC);
    } else {
      log.debug("Set SAMPLER_DROP for span {}", span.getSpanId());
      span.setSamplingPriority(PrioritySampling.SAMPLER_DROP, SamplingMechanism.APPSEC);
    }
  }

  private boolean shouldSample() {
    long now = System.currentTimeMillis();
    long lastTime = lastSampleTime.get();

    if (lastTime == -1 || now - lastTime >= rateInMilliseconds) {
      if (lastSampleTime.compareAndSet(lastTime, now)) {
        return true;
      }
    }
    return false;
  }
}
