package datadog.trace.api.sampling;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sampler allowing at most one sample per fixed interval, derived from a rate below 1 per second
 * (e.g. 1/300 means one sample every 5 minutes). Unlike a window-based adaptive sampler, there is
 * no rounding of the rate and no budget carried over from idle periods, so no burst can happen.
 */
public class IntervalSampler implements Sampler {
  private final long intervalNanos;
  private final TimeSource timeSource;
  private final AtomicLong nextSampleTicks;

  public IntervalSampler(double rate) {
    this(rate, SystemTimeSource.INSTANCE);
  }

  IntervalSampler(double rate, TimeSource timeSource) {
    this.intervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / rate);
    this.timeSource = timeSource;
    this.nextSampleTicks = new AtomicLong(timeSource.getNanoTicks());
  }

  @Override
  public boolean sample() {
    long now = timeSource.getNanoTicks();
    long next = nextSampleTicks.get();
    // difference comparison to be safe with nano ticks overflow
    if (now - next < 0) {
      return false;
    }
    // only one thread can win the slot for the current interval
    return nextSampleTicks.compareAndSet(next, now + intervalNanos);
  }

  @Override
  public boolean keep() {
    return true;
  }

  @Override
  public boolean drop() {
    return false;
  }
}
