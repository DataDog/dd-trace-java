package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.util.ClassNameFiltering;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ExceptionProbeManagerTest {
  @Test
  public void test() {
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(Collections.emptyList());
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    exceptionProbeManager.createProbesForException(fingerprint, exception.getStackTrace());
    assertTrue(exceptionProbeManager.isAlreadyInstrumented(fingerprint));
  }
}
