package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.util.ClassNameFiltering;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ExceptionProbeManagerTest {
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
  void frameFiltering() throws Exception {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(Arrays.asList("org.gradle.", "worker.org.gradle.", "org.junit."));
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("aff4c51e8660c93bae3343799a1c7d2d16309621144018c4d77b7e01a252e", fingerprint);
    exceptionProbeManager.createProbesForException(fingerprint, exception.getStackTrace());
    assertEquals(1, exceptionProbeManager.getProbes().size());
    ExceptionProbe exceptionProbe = exceptionProbeManager.getProbes().iterator().next();
    assertEquals(
        "com.datadog.debugger.exception.ExceptionProbeManagerTest",
        exceptionProbe.getWhere().getTypeName());
    // retry filtering the top frame
    classNameFiltering =
        new ClassNameFiltering(
            Arrays.asList(
                "org.gradle.",
                "worker.org.gradle.",
                "org.junit.",
                "com.datadog.debugger.exception.ExceptionProbeManagerTest"));
    exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("7a1e5e1bcc64ee26801d1471245eff6b6e8d7c61d0ea36fe85f3f75d79e42c", fingerprint);
    exceptionProbeManager.createProbesForException("", exception.getStackTrace());
    assertEquals(0, exceptionProbeManager.getProbes().size());
  }
}
