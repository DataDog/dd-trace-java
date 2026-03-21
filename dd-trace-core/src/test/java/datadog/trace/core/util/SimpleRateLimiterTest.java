package datadog.trace.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.time.ControllableTimeSource;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SimpleRateLimiterTest {

  @ParameterizedTest(name = "initial rate available at creation [rate={0}]")
  @ValueSource(ints = {10, 100, 1000})
  public void initialRateAvailableAtCreation(int rate) {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    SimpleRateLimiter limiter = new SimpleRateLimiter(rate, timeSource);

    for (int i = 0; i < rate; i++) {
      assertTrue(limiter.tryAcquire(), "failed for " + i);
    }

    assertFalse(limiter.tryAcquire());
  }

  @ParameterizedTest(name = "tokens never go beyond rate [rate={0}]")
  @ValueSource(ints = {10, 100, 1000})
  public void tokensNeverGoBeyondRate(int rate) {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    SimpleRateLimiter limiter = new SimpleRateLimiter(rate, timeSource);

    timeSource.advance(TimeUnit.SECONDS.toNanos(5));
    for (int i = 0; i < rate; i++) {
      assertTrue(limiter.tryAcquire(), "failed for " + i);
    }

    assertFalse(limiter.tryAcquire());
  }

  @ParameterizedTest(name = "tokens are consumed and replenished [rate={0}]")
  @ValueSource(ints = {10, 100, 1000})
  public void tokensAreConsumedAndReplenished(int rate) {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    SimpleRateLimiter limiter = new SimpleRateLimiter(rate, timeSource);
    long nanosIncrement = (long) (TimeUnit.SECONDS.toNanos(1) / (rate + 1)) + 1;

    for (int i = 0; i < rate; i++) {
      timeSource.advance(nanosIncrement);
      assertTrue(limiter.tryAcquire(), "failed for " + i);
    }

    assertFalse(limiter.tryAcquire());

    for (int i = 0; i < rate; i++) {
      timeSource.advance(nanosIncrement);
      assertTrue(limiter.tryAcquire(), "failed for " + i);
    }

    assertFalse(limiter.tryAcquire());
  }
}
