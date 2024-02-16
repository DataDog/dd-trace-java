package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.util.ClassNameFiltering;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ExceptionProbeManagerTest {
  private final RuntimeException exception = new RuntimeException("test");

  @Test
  public void instrumentStackTrace() {
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(Collections.emptyList());
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    exceptionProbeManager.createProbesForException(fingerprint, exception.getStackTrace());
    assertTrue(exceptionProbeManager.isAlreadyInstrumented(fingerprint));
  }

  @Test
  void instrumentSingleFrame() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(Arrays.asList("org.gradle.", "worker.org.gradle.", "org.junit."));
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);

    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("aa4a4dd768f6ef0fcc2b39a3bdedcbe44baff2e9dd0a779228db7bd8bf58", fingerprint);
    exceptionProbeManager.createProbesForException(fingerprint, exception.getStackTrace());
    assertEquals(1, exceptionProbeManager.getProbes().size());
    ExceptionProbe exceptionProbe = exceptionProbeManager.getProbes().iterator().next();
    assertEquals(
        "com.datadog.debugger.exception.ExceptionProbeManagerTest",
        exceptionProbe.getWhere().getTypeName());
  }

  @Test
  void filterAllFrames() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Arrays.asList(
                "org.gradle.",
                "worker.org.gradle.",
                "org.junit.",
                "com.datadog.debugger.exception.ExceptionProbeManagerTest"));
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("7a1e5e1bcc64ee26801d1471245eff6b6e8d7c61d0ea36fe85f3f75d79e42c", fingerprint);
    exceptionProbeManager.createProbesForException("", exception.getStackTrace());
    assertEquals(0, exceptionProbeManager.getProbes().size());
  }
}
