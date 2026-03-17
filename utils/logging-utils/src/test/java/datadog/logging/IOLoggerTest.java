package datadog.logging;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;

class IOLoggerTest {
  final IOLogger.Response response =
      new IOLogger.Response(404, "Not Found", "The thing you were looking for does not exist");
  final RuntimeException exception = new RuntimeException("Something went wrong!");

  Logger log;
  RatelimitedLogger rateLimitedLogger;
  IOLogger ioLogger;

  @BeforeEach
  void setUp() {
    log = mock(Logger.class);
    rateLimitedLogger = mock(RatelimitedLogger.class);
    ioLogger = new IOLogger(log, rateLimitedLogger);
  }

  @Test
  void successDebugLevel() {
    when(log.isDebugEnabled()).thenReturn(true);

    ioLogger.success("test {}", "message");

    verify(log).debug(eq(EXCLUDE_TELEMETRY), eq("test {}"), (Object[]) any());
  }

  @Test
  void successInfoLevel() {
    when(log.isDebugEnabled()).thenReturn(false);

    ioLogger.success("test {}", "message");

    verify(log, never()).debug(nullable(Marker.class), anyString(), (Object[]) any());
    verify(log, never()).info(nullable(Marker.class), anyString(), (Object[]) any());
  }

  @Test
  void errorDebugLevelMessage() {
    when(log.isDebugEnabled()).thenReturn(true);

    ioLogger.error("test message");

    verify(log).debug(EXCLUDE_TELEMETRY, "test message");
  }

  @Test
  void errorDebugLevelResponse() {
    when(log.isDebugEnabled()).thenReturn(true);

    ioLogger.error("test message", response);

    verify(log)
        .debug(
            eq(EXCLUDE_TELEMETRY),
            anyString(),
            eq("test message"),
            eq(404),
            eq("Not Found"),
            eq("The thing you were looking for does not exist"));
  }

  @Test
  void errorDebugLevelException() {
    when(log.isDebugEnabled()).thenReturn(true);

    ioLogger.error("test message", exception);

    verify(log).debug(EXCLUDE_TELEMETRY, "test message", exception);
  }

  @Test
  void errorInfoLevelMessage() {
    when(log.isDebugEnabled()).thenReturn(false);

    ioLogger.error("test message");

    verify(rateLimitedLogger).warn("test message");
  }

  @Test
  void errorInfoLevelResponse() {
    when(log.isDebugEnabled()).thenReturn(false);

    ioLogger.error("test message", response);

    verify(rateLimitedLogger)
        .warn(eq(EXCLUDE_TELEMETRY), anyString(), eq("test message"), eq(404), eq("Not Found"));
  }

  @Test
  void errorInfoLevelException() {
    when(log.isDebugEnabled()).thenReturn(false);

    ioLogger.error("test message", exception);

    verify(rateLimitedLogger)
        .warn(
            eq(EXCLUDE_TELEMETRY),
            anyString(),
            eq("test message"),
            eq("java.lang.RuntimeException"),
            eq("Something went wrong!"));
  }

  @Test
  void loggedErrorThenSuccessInfoLevel() {
    when(log.isDebugEnabled()).thenReturn(false);
    when(log.isInfoEnabled()).thenReturn(true);
    when(rateLimitedLogger.warn("test message")).thenReturn(true);

    ioLogger.error("test message");
    ioLogger.success("very successful");
    ioLogger.success("very successful again");

    verify(rateLimitedLogger).warn("test message");
    verify(log).info(eq(EXCLUDE_TELEMETRY), eq("very successful"), (Object[]) any());
    verify(log, never()).info(eq(EXCLUDE_TELEMETRY), eq("very successful again"), (Object[]) any());
  }

  @Test
  void unloggedErrorThenSuccessInfoLevel() {
    when(log.isDebugEnabled()).thenReturn(false);
    when(log.isInfoEnabled()).thenReturn(true);
    when(rateLimitedLogger.warn("test message")).thenReturn(false);

    ioLogger.error("test message");
    ioLogger.success("very successful");
    ioLogger.success("very successful again");

    verify(rateLimitedLogger).warn("test message");
    verify(log, never()).info(nullable(Marker.class), anyString(), (Object[]) any());
  }
}
