package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.NOPLogger;

class DiagnosticLoggerTest {
  private final Marker marker = mock(Marker.class);
  private final Throwable error = new IllegalStateException("diagnostic failure");

  @Test
  void forwardsMessagesAndArgumentsToTheBackend() throws Exception {
    Logger backend = mock(Logger.class);
    when(backend.isDebugEnabled()).thenReturn(true);
    DiagnosticLogger logger = loggerWithBackend(backend);

    assertTrue(logger.isDebugEnabled());
    assertSame(backend, logger.backend());
    logAllLevels(logger);

    verify(backend).debug("message");
    verify(backend).debug("one {}", "first");
    verify(backend).debug("two {} {}", "first", "second");
    verify(backend).debug("three {} {} {}", "first", "second", "third");
    verify(backend).warn("warning");
    verify(backend).warn("warning {}", new Object[] {"argument"});
    verify(backend).error("error", error);
    verify(backend).error(marker, "marked error", error);
  }

  @Test
  void respectsTheBackendDebugLevel() throws Exception {
    Logger backend = mock(Logger.class);
    assertFalse(loggerWithBackend(backend).isDebugEnabled());
    verify(backend).isDebugEnabled();
  }

  @Test
  void toleratesAnUnavailableBackend() throws Exception {
    DiagnosticLogger logger = loggerWithBackend(null);

    assertFalse(logger.isDebugEnabled());
    assertSame(NOPLogger.NOP_LOGGER, logger.backend());
    assertDoesNotThrow(() -> logAllLevels(logger));
  }

  @Test
  void containsFailuresFromEveryLoggingOverloadAndLevelCheck() throws Exception {
    Logger backend =
        mock(
            Logger.class,
            invocation -> {
              throw new AssertionError("broken backend");
            });
    DiagnosticLogger logger = loggerWithBackend(backend);

    assertFalse(logger.isDebugEnabled());
    assertDoesNotThrow(() -> logAllLevels(logger));
  }

  private void logAllLevels(DiagnosticLogger logger) {
    logger.debug("message");
    logger.debug("one {}", "first");
    logger.debug("two {} {}", "first", "second");
    logger.debug("three {} {} {}", "first", "second", "third");
    logger.warn("warning");
    logger.warn("warning {}", "argument");
    logger.error("error", error);
    logger.error(marker, "marked error", error);
  }

  private static DiagnosticLogger loggerWithBackend(Logger backend) throws Exception {
    Constructor<DiagnosticLogger> constructor =
        DiagnosticLogger.class.getDeclaredConstructor(Logger.class);
    constructor.setAccessible(true);
    return constructor.newInstance(backend);
  }
}
