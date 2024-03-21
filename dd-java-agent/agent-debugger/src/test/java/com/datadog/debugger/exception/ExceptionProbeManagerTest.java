package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.util.ClassNameFiltering;
import java.time.Duration;
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
    assertFalse(exceptionProbeManager.getProbes().isEmpty());
  }

  @Test
  void instrumentSingleFrame() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(Arrays.asList("org.gradle.", "worker.org.gradle.", "org.junit."));
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);

    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("ca4d9f3a1033d7262a89855f4b5cbdc225ed63c592c6cdf83fc5a88589e5fb", fingerprint);
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

  static void waitForInstrumentation(
      ExceptionProbeManager exceptionProbeManager, String fingerprint) {
    Duration timeout = Duration.ofSeconds(30);
    Duration sleepTime = Duration.ofMillis(10);
    long count = timeout.toMillis() / sleepTime.toMillis();
    while (count-- > 0 && !exceptionProbeManager.isAlreadyInstrumented(fingerprint)) {
      try {
        Thread.sleep(sleepTime.toMillis());
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    assertTrue(exceptionProbeManager.isAlreadyInstrumented(fingerprint));
  }
}
