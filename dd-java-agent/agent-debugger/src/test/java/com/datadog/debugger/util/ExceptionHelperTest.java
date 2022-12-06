package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import datadog.trace.relocate.api.RatelimitedLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

@ExtendWith(MockitoExtension.class)
public class ExceptionHelperTest {

  @Mock Logger logger;

  @Mock RatelimitedLogger ratelimitedLogger;

  @Test
  public void testWarn() {
    when(logger.isDebugEnabled()).thenReturn(false);
    doAnswer(this::logWarn).when(logger).warn(anyString(), ArgumentMatchers.<Object[]>any());
    ExceptionHelper.logException(
        logger, new RuntimeException("this is an exception"), "Error {} {}", "param1", "param2");
  }

  @Test
  public void testDebug() {
    when(logger.isDebugEnabled()).thenReturn(true);
    doAnswer(this::logDebug).when(logger).debug(anyString(), ArgumentMatchers.<Object[]>any());
    ExceptionHelper.logException(
        logger, new RuntimeException("this is an exception"), "Error {} {}", "param1", "param2");
  }

  @Test
  public void rateLimitWarn() {
    when(logger.isDebugEnabled()).thenReturn(false);
    doAnswer(this::logWarn)
        .when(ratelimitedLogger)
        .warn(anyString(), ArgumentMatchers.<Object[]>any());
    ExceptionHelper.rateLimitedLogException(
        ratelimitedLogger,
        logger,
        new RuntimeException("this is an exception"),
        "Error {} {}",
        "param1",
        "param2");
  }

  @Test
  public void rateLimitDebug() {
    when(logger.isDebugEnabled()).thenReturn(true);
    doAnswer(this::logDebug).when(logger).debug(anyString(), ArgumentMatchers.<Object[]>any());
    ExceptionHelper.rateLimitedLogException(
        ratelimitedLogger,
        logger,
        new RuntimeException("this is an exception"),
        "Error {} {}",
        "param1",
        "param2");
  }

  private Object logDebug(InvocationOnMock invocation) {
    Object[] args = invocation.getArguments();
    assertEquals(4, args.length);
    assertEquals("Error {} {}", args[0]);
    assertEquals("param1", args[1]);
    assertEquals("param2", args[2]);
    assertTrue(args[3] instanceof RuntimeException);
    assertEquals("this is an exception", ((RuntimeException) args[3]).getMessage());
    return null;
  }

  private Object logWarn(InvocationOnMock invocation) {
    Object[] args = invocation.getArguments();
    assertEquals(3, args.length);
    assertEquals("Error {} {} java.lang.RuntimeException: this is an exception", args[0]);
    assertEquals("param1", args[1]);
    assertEquals("param2", args[2]);
    return null;
  }
}
