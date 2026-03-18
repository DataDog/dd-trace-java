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
  private final IOLogger.Response response =
      new IOLogger.Response(404, "Not Found", "The thing you were looking for does not exist");
  private final RuntimeException exception = new RuntimeException("Something went wrong!");

  private Logger log;
  private RatelimitedLogger rateLimitedLogger;
  private IOLogger ioLogger;

  @BeforeEach
  void setUp() {
    this.log = mock(Logger.class);
    this.rateLimitedLogger = mock(RatelimitedLogger.class);
    this.ioLogger = new IOLogger(this.log, this.rateLimitedLogger);
  }

  @Test
  void successDebugLevel() {
    when(this.log.isDebugEnabled()).thenReturn(true);

    this.ioLogger.success("test {}", "message");

    verify(this.log).debug(eq(EXCLUDE_TELEMETRY), eq("test {}"), (Object[]) any());
  }

  @Test
  void successInfoLevel() {
    when(this.log.isDebugEnabled()).thenReturn(false);

    this.ioLogger.success("test {}", "message");

    verify(this.log, never()).debug(nullable(Marker.class), anyString(), (Object[]) any());
    verify(this.log, never()).info(nullable(Marker.class), anyString(), (Object[]) any());
  }

  @Test
  void errorDebugLevelMessage() {
    when(this.log.isDebugEnabled()).thenReturn(true);

    this.ioLogger.error("test message");

    verify(this.log).debug(EXCLUDE_TELEMETRY, "test message");
  }

  @Test
  void errorDebugLevelResponse() {
    when(this.log.isDebugEnabled()).thenReturn(true);

    this.ioLogger.error("test message", this.response);

    verify(this.log)
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
    when(this.log.isDebugEnabled()).thenReturn(true);

    this.ioLogger.error("test message", this.exception);

    verify(this.log).debug(EXCLUDE_TELEMETRY, "test message", this.exception);
  }

  @Test
  void errorInfoLevelMessage() {
    when(this.log.isDebugEnabled()).thenReturn(false);

    this.ioLogger.error("test message");

    verify(this.rateLimitedLogger).warn("test message");
  }

  @Test
  void errorInfoLevelResponse() {
    when(this.log.isDebugEnabled()).thenReturn(false);

    this.ioLogger.error("test message", this.response);

    verify(this.rateLimitedLogger)
        .warn(eq(EXCLUDE_TELEMETRY), anyString(), eq("test message"), eq(404), eq("Not Found"));
  }

  @Test
  void errorInfoLevelException() {
    when(this.log.isDebugEnabled()).thenReturn(false);

    this.ioLogger.error("test message", this.exception);

    verify(this.rateLimitedLogger)
        .warn(
            eq(EXCLUDE_TELEMETRY),
            anyString(),
            eq("test message"),
            eq("java.lang.RuntimeException"),
            eq("Something went wrong!"));
  }

  @Test
  void loggedErrorThenSuccessInfoLevel() {
    when(this.log.isDebugEnabled()).thenReturn(false);
    when(this.log.isInfoEnabled()).thenReturn(true);
    when(this.rateLimitedLogger.warn("test message")).thenReturn(true);

    this.ioLogger.error("test message");
    this.ioLogger.success("very successful");
    this.ioLogger.success("very successful again");

    verify(this.rateLimitedLogger).warn("test message");
    verify(this.log).info(eq(EXCLUDE_TELEMETRY), eq("very successful"), (Object[]) any());
    verify(this.log, never())
        .info(eq(EXCLUDE_TELEMETRY), eq("very successful again"), (Object[]) any());
  }

  @Test
  void unloggedErrorThenSuccessInfoLevel() {
    when(this.log.isDebugEnabled()).thenReturn(false);
    when(this.log.isInfoEnabled()).thenReturn(true);
    when(this.rateLimitedLogger.warn("test message")).thenReturn(false);

    this.ioLogger.error("test message");
    this.ioLogger.success("very successful");
    this.ioLogger.success("very successful again");

    verify(this.rateLimitedLogger).warn("test message");
    verify(this.log, never()).info(nullable(Marker.class), anyString(), (Object[]) any());
  }
}
