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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;

class RateLimitedLoggerTest {
  final RuntimeException exception = new RuntimeException("bad thing");

  @Test
  void debugLevel() {
    Logger log = mock(Logger.class);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    when(log.isDebugEnabled()).thenReturn(true);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, MINUTES, timeSource);

    rateLimitedLog.warn("test {} {}", "message", exception);
    rateLimitedLog.warn("test {} {}", "message", exception);

    verify(log, times(2)).warn(nullable(Marker.class), eq("test {} {}"), (Object[]) any());
  }

  @Test
  void defaultWarningOnce() {
    Logger log = mock(Logger.class);
    when(log.isWarnEnabled()).thenReturn(true);
    when(log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger defaultRateLimitedLog = new RatelimitedLogger(log, 5, MINUTES);

    boolean firstLog = defaultRateLimitedLog.warn("test {} {}", "message", exception);
    boolean secondLog = defaultRateLimitedLog.warn("test {} {}", "message", exception);

    verify(log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 5 minutes)"),
            (Object[]) any());
    assertTrue(firstLog);
    assertFalse(secondLog);
  }

  @Test
  void warningOnce() {
    Logger log = mock(Logger.class);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    when(log.isWarnEnabled()).thenReturn(true);
    when(log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MINUTES, timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertTrue(firstLog);

    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertFalse(secondLog);

    verify(log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 1 minute)"),
            (Object[]) any());
  }

  @Test
  void warningOnceNegativeTime() {
    Logger log = mock(Logger.class);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(Long.MIN_VALUE);
    when(log.isWarnEnabled()).thenReturn(true);
    when(log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, NANOSECONDS, timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertTrue(firstLog);

    timeSource.advance(5 - 1);
    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertFalse(secondLog);

    verify(log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 5 nanoseconds)"),
            (Object[]) any());
  }

  @Test
  void warningOnceZeroTime() {
    Logger log = mock(Logger.class);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(0);
    when(log.isWarnEnabled()).thenReturn(true);
    when(log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, NANOSECONDS, timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertTrue(firstLog);

    timeSource.advance(1);
    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertFalse(secondLog);

    verify(log)
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 5 nanoseconds)"),
            (Object[]) any());
  }

  @Test
  void warningTwice() {
    Logger log = mock(Logger.class);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    when(log.isWarnEnabled()).thenReturn(true);
    when(log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 7, NANOSECONDS, timeSource);

    boolean firstLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertTrue(firstLog);

    timeSource.advance(7);
    boolean secondLog = rateLimitedLog.warn("test {} {}", "message", exception);
    assertTrue(secondLog);

    verify(log, times(2))
        .warn(
            nullable(Marker.class),
            eq("test {} {} (Will not log warnings for 7 nanoseconds)"),
            (Object[]) any());
  }

  @Test
  void noArgs() {
    Logger log = mock(Logger.class);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    when(log.isWarnEnabled()).thenReturn(true);
    when(log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MILLISECONDS, timeSource);

    rateLimitedLog.warn("test");

    verify(log)
        .warn(
            nullable(Marker.class),
            eq("test (Will not log warnings for 1 millisecond)"),
            (Object[]) any());
  }

  @Test
  void withMarker() {
    Logger log = mock(Logger.class);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    when(log.isWarnEnabled()).thenReturn(true);
    when(log.isDebugEnabled()).thenReturn(false);
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MILLISECONDS, timeSource);

    rateLimitedLog.warn(LogCollector.SEND_TELEMETRY, "test");

    verify(log)
        .warn(
            eq(LogCollector.SEND_TELEMETRY),
            eq("test (Will not log warnings for 1 millisecond)"),
            (Object[]) any());
  }
}
