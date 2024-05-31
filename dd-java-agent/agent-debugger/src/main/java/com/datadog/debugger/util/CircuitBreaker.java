package com.datadog.debugger.util;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// CircuitBreaker is a simple circuit breaker implementation that allows a certain number of trips
// within a time window. If the number of trips exceeds the limit, the circuit breaker will trip and
// return false until the time window has passed.
public class CircuitBreaker {
  private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreaker.class);

  private final int maxTrips;
  private final Duration timeWindow;
  private AtomicInteger count = new AtomicInteger(0);
  private volatile long lastResetTime = System.currentTimeMillis();
  private volatile long lastLoggingTime = System.currentTimeMillis();

  public CircuitBreaker(int maxTrips, Duration timeWindow) {
    this.maxTrips = maxTrips;
    this.timeWindow = timeWindow;
  }

  public boolean trip() {
    int localCount = count.incrementAndGet();
    if (localCount > maxTrips) {
      long currentTime = System.currentTimeMillis();
      if (currentTime - lastLoggingTime > Duration.ofMinutes(1).toMillis()) {
        lastLoggingTime = currentTime;
        LOGGER.debug("Circuit breaker opened");
      }
      if (currentTime - lastResetTime > timeWindow.toMillis()) {
        lastResetTime = currentTime;
        localCount = 1;
        count.set(localCount);
      }
    }
    return localCount <= maxTrips;
  }
}
