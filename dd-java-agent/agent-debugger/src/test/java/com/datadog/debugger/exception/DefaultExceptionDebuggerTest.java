package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DefaultExceptionDebuggerTest {

  @Test
  public void test() {
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager();
    DefaultExceptionDebugger exceptionDebugger =
        new DefaultExceptionDebugger(exceptionProbeManager);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception);
    exceptionDebugger.handleException(exception);
    exceptionDebugger.handleException(exception);
    assertTrue(exceptionProbeManager.isAlreadyInstrumented(fingerprint));
  }
}
