package datadog.trace.api.sampling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.time.ControllableTimeSource;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class IntervalSamplerTest {

  @TableTest({
    "scenario        | intervalSeconds",
    "1 per 2 seconds | 2              ",
    "1 per 5 minutes | 300            ",
    "1 per hour      | 3600           "
  })
  void sampleOncePerInterval(int intervalSeconds) {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    IntervalSampler sampler = new IntervalSampler(1.0 / intervalSeconds, timeSource);
    long intervalNanos = TimeUnit.SECONDS.toNanos(intervalSeconds);
    assertTrue(sampler.sample());
    assertFalse(sampler.sample());
    timeSource.advance(intervalNanos - 1);
    assertFalse(sampler.sample());
    timeSource.advance(1);
    assertTrue(sampler.sample());
    assertFalse(sampler.sample());
  }

  @Test
  void noBurstAfterIdlePeriod() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    IntervalSampler sampler = new IntervalSampler(1.0 / 300, timeSource);
    assertTrue(sampler.sample());
    timeSource.advance(TimeUnit.HOURS.toNanos(10));
    assertTrue(sampler.sample());
    assertFalse(sampler.sample());
  }

  @Test
  void nanoTicksOverflow() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1));
    IntervalSampler sampler = new IntervalSampler(1.0 / 300, timeSource);
    assertTrue(sampler.sample());
    timeSource.advance(TimeUnit.SECONDS.toNanos(299));
    assertFalse(sampler.sample());
    timeSource.advance(TimeUnit.SECONDS.toNanos(1));
    assertTrue(sampler.sample());
  }
}
