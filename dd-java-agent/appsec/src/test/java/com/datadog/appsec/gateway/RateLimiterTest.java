package com.datadog.appsec.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.time.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RateLimiterTest {

  private static final long ONE_SECOND = 1_000_000_000L;

  private int throttledCounter;
  private TimeSource timeSource;
  private RateLimiter testee;

  @BeforeEach
  void setUp() {
    throttledCounter = 0;
    timeSource = mock(TimeSource.class);
    testee = new RateLimiter(10, timeSource, () -> throttledCounter++);
  }

  @Test
  void limitIsRespectedInASingleInterval() {
    when(timeSource.getNanoTicks()).thenReturn(System.nanoTime());

    int count = countNotThrottled(testee, 15);

    verifyNanoTicksCalled(15);
    assertEquals(10, count);
    assertEquals(5, throttledCounter);
  }

  @Test
  void limitConsidersFractionOfPreviousInterval() {
    long initialTime = 0;

    when(timeSource.getNanoTicks()).thenReturn(initialTime);
    int count = countNotThrottled(testee, 8);

    verifyNanoTicksCalled(8);
    assertEquals(8, count);

    when(timeSource.getNanoTicks()).thenReturn(initialTime + 1_500_000_000L);
    count += countNotThrottled(testee, 20);

    verifyNanoTicksCalled(20);
    // 4 events from previous period are considered.
    assertEquals(8 + (10 - 4), count);
  }

  @Test
  void limitMayBeAppliedEvenWhen1SecAhead() {
    long initialTime = 0;

    when(timeSource.getNanoTicks()).thenReturn(initialTime);
    int count = countNotThrottled(testee, 10);

    verifyNanoTicksCalled(10);
    assertEquals(10, count);

    when(timeSource.getNanoTicks()).thenReturn(initialTime + ONE_SECOND);
    count += countNotThrottled(testee, 1);

    verifyNanoTicksCalled(1);
    assertEquals(10, count);
  }

  @Test
  void limitConsidersFractionOfPreviousIntervalUshortWrapAround() {
    long initialTime = ONE_SECOND;

    // this is needed because of initial state of the rate limiter:
    when(timeSource.getNanoTicks()).thenReturn(-42_000_000_000L);
    testee.isThrottled();
    verifyNanoTicksCalled(1);

    when(timeSource.getNanoTicks()).thenReturn(initialTime);
    int count = countNotThrottled(testee, 8);

    verifyNanoTicksCalled(8);
    assertEquals(8, count);

    when(timeSource.getNanoTicks()).thenReturn(initialTime + 1_500_000_000L);
    count += countNotThrottled(testee, 20);

    verifyNanoTicksCalled(20);
    // 4 events from previous period are considered.
    assertEquals(8 + (10 - 4), count);
  }

  @Test
  void limitIgnoresFractionOfOldInterval() {
    long initialTime = 0;

    when(timeSource.getNanoTicks()).thenReturn(initialTime);
    int count = countNotThrottled(testee, 8);

    verifyNanoTicksCalled(8);
    assertEquals(8, count);

    when(timeSource.getNanoTicks()).thenReturn(initialTime + 2_000_000_000L);
    count += countNotThrottled(testee, 20);

    verifyNanoTicksCalled(20);
    assertEquals(8 + 10, count);

    when(timeSource.getNanoTicks()).thenReturn(initialTime);
    count += countNotThrottled(testee, 20);

    verifyNanoTicksCalled(20);
    assertEquals(8 + 10 + 10, count);
  }

  @ParameterizedTest(name = "if 1 sec behind reread the time with initialTime={0}")
  @ValueSource(longs = {ONE_SECOND, 0L})
  void if1SecBehindRereadTheTime(long initialTime) {
    when(timeSource.getNanoTicks()).thenReturn(initialTime);
    int count = countNotThrottled(testee, 9);

    verifyNanoTicksCalled(9);

    when(timeSource.getNanoTicks()).thenReturn(initialTime - ONE_SECOND, initialTime);
    count += countNotThrottled(testee, 1);

    verifyNanoTicksCalled(2);
    assertEquals(10, count);

    when(timeSource.getNanoTicks()).thenReturn(initialTime - ONE_SECOND, initialTime);
    count += countNotThrottled(testee, 1);

    verifyNanoTicksCalled(2);
    assertEquals(10, count);
  }

  @Test
  void ifStill1SecBehindAfterRereadAssumeTheTimeHasWrappedAround() {
    long initialTime = ONE_SECOND;

    when(timeSource.getNanoTicks()).thenReturn(initialTime);
    int count = countNotThrottled(testee, 10);

    verifyNanoTicksCalled(10);

    when(timeSource.getNanoTicks()).thenReturn(initialTime - ONE_SECOND);
    count += countNotThrottled(testee, 1);

    verifyNanoTicksCalled(2);
    assertEquals(11, count);
  }

  @Test
  void testNoOP() {
    TimeSource zeroTimeSource = mock(TimeSource.class);
    when(zeroTimeSource.getNanoTicks()).thenReturn(0L);
    RateLimiter rateLimiter =
        new RateLimiter(10, zeroTimeSource, RateLimiter.ThrottledCallback.NOOP);

    int count = countNotThrottled(rateLimiter, 15);

    assertEquals(10, count);
  }

  private static int countNotThrottled(RateLimiter rateLimiter, int attempts) {
    int count = 0;
    for (int attempt = 0; attempt < attempts; attempt++) {
      if (!rateLimiter.isThrottled()) {
        count++;
      }
    }
    return count;
  }

  /** Verifies the exact number of time reads since the last check, then resets the counter. */
  private void verifyNanoTicksCalled(int expectedCalls) {
    verify(timeSource, times(expectedCalls)).getNanoTicks();
    clearInvocations(timeSource);
  }
}
