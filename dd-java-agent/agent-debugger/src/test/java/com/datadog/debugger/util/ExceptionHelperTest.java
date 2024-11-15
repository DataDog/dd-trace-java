package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import datadog.trace.relocate.api.RatelimitedLogger;
import java.util.ArrayDeque;
import java.util.Deque;
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

  @Test
  public void foldExceptionStackTrace() {
    String strStackTrace = ExceptionHelper.foldExceptionStackTrace(new Exception());
    assertTrue(
        strStackTrace.startsWith(
            "java.lang.Exception at com.datadog.debugger.util.ExceptionHelperTest.foldExceptionStackTrace(ExceptionHelperTest.java:74) at "),
        strStackTrace);
    assertFalse(strStackTrace.contains("\n"));
    assertFalse(strStackTrace.contains("\t"));
    assertFalse(strStackTrace.contains("\r"));
  }

  @Test
  public void innerMostException() {
    Exception ex = new RuntimeException("test1");
    Throwable innerMost = ExceptionHelper.getInnerMostThrowable(ex);
    assertEquals(ex, innerMost);
    Exception nested = new RuntimeException("test3", new RuntimeException("test2", ex));
    innerMost = ExceptionHelper.getInnerMostThrowable(nested);
    assertEquals(ex, innerMost);
    Deque<Throwable> exceptions = new ArrayDeque<>();
    innerMost = ExceptionHelper.getInnerMostThrowable(nested, exceptions);
    assertEquals(ex, innerMost);
    assertEquals(3, exceptions.size());
    assertEquals(nested, exceptions.pollLast());
    assertEquals(nested.getCause(), exceptions.pollLast());
    assertEquals(nested.getCause().getCause(), exceptions.pollLast());
  }

  @Test
  public void flattenStackTrace() {
    Throwable simpleException =
        new MockException(
            "oops!",
            new StackTraceElement[] {
              new StackTraceElement("MyClass1", "myMethod1", "file1.java", 1)
            });
    Throwable nestedException =
        new MockException(
            "oops!",
            new StackTraceElement[] {
              new StackTraceElement("MyClass2", "myMethod2", "file2.java", 2)
            },
            simpleException);
    StackTraceElement[] stack = ExceptionHelper.flattenStackTrace(simpleException);
    assertEquals(1, stack.length);
    stack = ExceptionHelper.flattenStackTrace(nestedException);
    assertEquals(2, stack.length);
  }

  @Test
  public void createThrowableMapping() {
    Throwable nestedException = createNestException();
    Throwable innerMostThrowable = ExceptionHelper.getInnerMostThrowable(nestedException);
    int[] mapping = ExceptionHelper.createThrowableMapping(innerMostThrowable, nestedException);
    StackTraceElement[] flattenedTrace = ExceptionHelper.flattenStackTrace(nestedException);
    for (int i = 0; i < mapping.length; i++) {
      assertEquals(
          flattenedTrace[mapping[i]].getClassName(),
          innerMostThrowable.getStackTrace()[i].getClassName());
    }
  }

  @Test
  public void createThrowableMappingRecursive() {
    Throwable nestedException = createNestExceptionRecursive(4);
    Throwable innerMostThrowable = ExceptionHelper.getInnerMostThrowable(nestedException);
    int[] mapping = ExceptionHelper.createThrowableMapping(innerMostThrowable, nestedException);
    StackTraceElement[] flattenedTrace = ExceptionHelper.flattenStackTrace(nestedException);
    for (int i = 0; i < mapping.length; i++) {
      assertEquals(
          flattenedTrace[mapping[i]].getClassName(),
          innerMostThrowable.getStackTrace()[i].getClassName());
    }
  }

  private RuntimeException createNestExceptionRecursive(int depth) {
    if (depth == 0) {
      return createNestException();
    }
    return createNestExceptionRecursive(depth - 1);
  }

  private RuntimeException createNestException() {
    return new RuntimeException("test3", createTest2Exception(createTest1Exception()));
  }

  private RuntimeException createTest1Exception() {
    return new RuntimeException("test1");
  }

  private RuntimeException createTest2Exception(Throwable cause) {
    return new RuntimeException("test2", cause);
  }

  private static class MockException extends Exception {
    private final StackTraceElement[] stackTrace;

    public MockException(String message, StackTraceElement[] stackTrace) {
      this(message, stackTrace, null);
    }

    public MockException(String message, StackTraceElement[] stackTrace, Throwable cause) {
      super(message, cause);
      this.stackTrace = stackTrace;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
      return stackTrace;
    }
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
