package datadog.trace.common.sampling;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import java.util.concurrent.atomic.AtomicLong;

/** Sampler that samples traces based on a fixed time rate in milliseconds. */
public class TimeSampler implements Sampler, PrioritySampler {

  private final AtomicLong lastSampleTime;
  private final int rateInMilliseconds;

  public TimeSampler(int rateInMilliseconds) {
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
      span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.DEFAULT);
    } else {
      span.setSamplingPriority(PrioritySampling.SAMPLER_DROP, SamplingMechanism.DEFAULT);
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
