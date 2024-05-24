package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  @Test
  void noBreaker() {
    CircuitBreaker cb = new CircuitBreaker(3, Duration.ofMillis(10));
    for (int i = 0; i < 10; i++) {
      assertTrue(cb.trip());
      LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
    }
  }

  @Test
  void breaker() {
    CircuitBreaker cb = new CircuitBreaker(3, Duration.ofMillis(200));
    for (int i = 0; i < 3; i++) {
      assertTrue(cb.trip());
    }
    for (int i = 0; i < 100; i++) {
      assertFalse(cb.trip());
    }
    LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
    for (int i = 0; i < 3; i++) {
      assertTrue(cb.trip());
    }
    for (int i = 0; i < 100; i++) {
      assertFalse(cb.trip());
    }
  }
}
