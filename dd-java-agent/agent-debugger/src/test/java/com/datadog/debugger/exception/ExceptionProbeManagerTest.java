package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.api.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ExceptionProbeManagerTest {
  private final RuntimeException exception = new RuntimeException("test");

  @Test
  public void instrumentStackTrace() {
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    exceptionProbeManager.createProbesForException(exception.getStackTrace(), 0);
    assertFalse(exceptionProbeManager.getProbes().isEmpty());
  }

  @Test
  void instrumentSingleFrame() {
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Stream.of(
                    "java.",
                    "jdk.",
                    "sun.",
                    "com.sun.",
                    "org.gradle.",
                    "worker.org.gradle.",
                    "org.junit.")
                .collect(Collectors.toSet()));
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);

    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("4974b2b4853e6152d8f218fb79a42a761a45335447e22e53d75f5325e742655", fingerprint);
    exceptionProbeManager.createProbesForException(exception.getStackTrace(), 0);
    assertEquals(1, exceptionProbeManager.getProbes().size());
    ExceptionProbe exceptionProbe = exceptionProbeManager.getProbes().iterator().next();
    assertEquals(
        "com.datadog.debugger.exception.ExceptionProbeManagerTest",
        exceptionProbe.getWhere().getTypeName());
  }

  @Test
  void filterAllFrames() {
    Config config = mock(Config.class);
    when(config.getThirdPartyExcludes()).thenReturn(Collections.emptySet());
    when(config.getThirdPartyIncludes())
        .thenReturn(
            Stream.of(
                    "org.gradle.",
                    "worker.org.gradle.",
                    "org.junit.",
                    "com.datadog.debugger.exception.ExceptionProbeManagerTest")
                .collect(Collectors.toSet()));
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(config);
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("7a1e5e1bcc64ee26801d1471245eff6b6e8d7c61d0ea36fe85f3f75d79e42c", fingerprint);
    exceptionProbeManager.createProbesForException(exception.getStackTrace(), 0);
    assertEquals(0, exceptionProbeManager.getProbes().size());
  }

  @Test
  void lastCapture() {
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    exceptionProbeManager.addFingerprint(fingerprint);
    assertTrue(exceptionProbeManager.shouldCaptureException(fingerprint));
    exceptionProbeManager.updateLastCapture(fingerprint);
    assertFalse(exceptionProbeManager.shouldCaptureException(fingerprint));
    Clock clock =
        Clock.fixed(Instant.now().plus(Duration.ofMinutes(61)), Clock.systemUTC().getZone());
    assertTrue(exceptionProbeManager.shouldCaptureException(fingerprint, clock));
  }

  @Test
  void maxFrames() {
    RuntimeException deepException = level1();
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Stream.of(
                    "java.",
                    "jdk.",
                    "sun.",
                    "com.sun.",
                    "org.gradle.",
                    "worker.org.gradle.",
                    "org.junit.")
                .collect(Collectors.toSet()));
    ExceptionProbeManager exceptionProbeManager =
        new ExceptionProbeManager(classNameFiltering, Duration.ofHours(1), Clock.systemUTC(), 3);
    exceptionProbeManager.createProbesForException(deepException.getStackTrace(), 0);
    assertEquals(3, exceptionProbeManager.getProbes().size());
  }

  RuntimeException level1() {
    return level2();
  }

  RuntimeException level2() {
    return level3();
  }

  RuntimeException level3() {
    return new RuntimeException("3 level deep exception");
  }
}
