package datadog.logging;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.api.time.ControllableTimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;

class RateLimitedLoggerTest {
  private static final RuntimeException EXCEPTION = new RuntimeException("bad thing");

  private Logger log;
  private ControllableTimeSource timeSource;

  @BeforeEach
  void setUp() {
    this.log = mock(Logger.class);
    this.timeSource = new ControllableTimeSource();
  }

  @Test
  void debugLevel() {
    when(this.log.isDebugEnabled()).thenReturn(true);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(this.log, 5, MINUTES, this.timeSource);

    rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    rateLimitedLog.warn("test {} {}", "message", EXCEPTION);

    verify(this.log, times(2)).warn(nullable(Marker.class), eq("test {} {}"), (Object[]) any());
  }

  @Test
  void defaultWarningOnce() {
    when(this.log.isWarnEnabled()).thenReturn(true);
    when(this.log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger defaultRateLimitedLog = new RatelimitedLogger(this.log, 5, MINUTES);

    boolean firstLog = defaultRateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    boolean secondLog = defaultRateLimitedLog.warn("test {} {}", "message", EXCEPTION);

    verify(this.log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 5 minutes)"),
            (Object[]) any());
    assertTrue(firstLog);
    assertFalse(secondLog);
  }

  @Test
  void warningOnce() {
    when(this.log.isWarnEnabled()).thenReturn(true);
    when(this.log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(this.log, 1, MINUTES, this.timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertTrue(firstLog);

    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertFalse(secondLog);

    verify(this.log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 1 minute)"),
            (Object[]) any());
  }

  @Test
  void warningOnceNegativeTime() {
    this.timeSource.set(Long.MIN_VALUE);
    when(this.log.isWarnEnabled()).thenReturn(true);
    when(this.log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog =
        new RatelimitedLogger(this.log, 5, NANOSECONDS, this.timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertTrue(firstLog);

    this.timeSource.advance(5 - 1);
    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertFalse(secondLog);

    verify(this.log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 5 nanoseconds)"),
            (Object[]) any());
  }

  @Test
  void warningOnceZeroTime() {
    this.timeSource.set(0);
    when(this.log.isWarnEnabled()).thenReturn(true);
    when(this.log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog =
        new RatelimitedLogger(this.log, 5, NANOSECONDS, this.timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertTrue(firstLog);

    this.timeSource.advance(1);
    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertFalse(secondLog);

    verify(this.log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 5 nanoseconds)"),
            (Object[]) any());
  }

  @Test
  void warningTwice() {
    when(this.log.isWarnEnabled()).thenReturn(true);
    when(this.log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog =
        new RatelimitedLogger(this.log, 7, NANOSECONDS, this.timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertTrue(firstLog);

    this.timeSource.advance(7);
    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", EXCEPTION);
    assertTrue(secondLog);

    verify(this.log, times(2))
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 7 nanoseconds)"),
            (Object[]) any());
  }

  @Test
  void noArgs() {
    when(this.log.isWarnEnabled()).thenReturn(true);
    when(this.log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MILLISECONDS, this.timeSource);

    rateLimitedLog.warn("test");

    verify(this.log)
        .warn(
            nullable(Marker.class),
            eq("test (Will not log warnings for 1 millisecond)"),
            (Object[]) any());
  }

  @Test
  void withMarker() {
    when(this.log.isWarnEnabled()).thenReturn(true);
    when(this.log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog =
        new RatelimitedLogger(this.log, 1, MILLISECONDS, this.timeSource);

    rateLimitedLog.warn(LogCollector.SEND_TELEMETRY, "test");

    verify(this.log)
        .warn(
            eq(LogCollector.SEND_TELEMETRY),
            eq("test (Will not log warnings for 1 millisecond)"),
            (Object[]) any());
  }
}
