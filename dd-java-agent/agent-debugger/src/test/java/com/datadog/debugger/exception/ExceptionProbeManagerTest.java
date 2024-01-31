package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExceptionProbeManagerTest {
  @Test
  public void test() {
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager();
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception);
    exceptionProbeManager.instrument(fingerprint, exception.getStackTrace());
    assertTrue(exceptionProbeManager.isAlreadyInstrumented(fingerprint));
  }
}
