package com.datadog.debugger.exception;

import static com.datadog.debugger.util.TestHelper.assertWithTimeout;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

class ExceptionProbeManagerTest {
  private static final @NotNull Set<String> FILTERED_PACKAGES =
      new HashSet<>(
          asList(
              "java.",
              "jdk.",
              "sun.",
              "com.sun.",
              "org.gradle.",
              "worker.org.gradle.",
              "org.junit."));
  private final RuntimeException exception = new RuntimeException("test");

  @Test
  public void instrumentStackTrace() {
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    RuntimeException exception = new RuntimeException("test");
    exceptionProbeManager.createProbesForException(exception.getStackTrace(), 0);
    assertFalse(exceptionProbeManager.getProbes().isEmpty());
  }

  @Test
  void instrumentSingleFrame() {
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(FILTERED_PACKAGES);
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);

    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    assertEquals("44cbdcfe32eea21c3523e4a5885daabf5b8c9428cc0f613be5ff29663465ff9", fingerprint);
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
    Instant lastCapture = exceptionProbeManager.getLastCapture(fingerprint);
    assertTrue(exceptionProbeManager.shouldCaptureException(lastCapture));
    exceptionProbeManager.updateLastCapture(fingerprint);
    lastCapture = exceptionProbeManager.getLastCapture(fingerprint);
    assertFalse(exceptionProbeManager.shouldCaptureException(lastCapture));
    Clock clock =
        Clock.fixed(Instant.now().plus(Duration.ofMinutes(61)), Clock.systemUTC().getZone());
    lastCapture = exceptionProbeManager.getLastCapture(fingerprint);
    assertTrue(exceptionProbeManager.shouldCaptureException(lastCapture, clock));
  }

  @Test
  void maxFrames() {
    RuntimeException deepException = level1();
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(FILTERED_PACKAGES);
    ExceptionProbeManager exceptionProbeManager =
        new ExceptionProbeManager(classNameFiltering, Duration.ofHours(1), Clock.systemUTC(), 3);
    exceptionProbeManager.createProbesForException(deepException.getStackTrace(), 0);
    assertEquals(3, exceptionProbeManager.getProbes().size());
  }

  @Test
  public void removeExceptionProbeOnHotLoop() {
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(FILTERED_PACKAGES);
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    AtomicBoolean configApplied = new AtomicBoolean(false);
    DefaultExceptionDebugger defaultExceptionDebugger = mock(DefaultExceptionDebugger.class);
    doAnswer(
            invocationOnMock -> {
              configApplied.set(true);
              return null;
            })
        .when(defaultExceptionDebugger)
        .applyExceptionConfiguration();
    exceptionProbeManager.setDefaultExceptionDebugger(defaultExceptionDebugger);
    exceptionProbeManager.createProbesForException(exception.getStackTrace(), 0);
    assertEquals(1, exceptionProbeManager.getProbes().size());
    ExceptionProbe exceptionProbe = exceptionProbeManager.getProbes().iterator().next();
    // simulate a code hot loop
    for (int i = 0; i < 2000; i++) {
      CapturedContext context = mock(CapturedContext.class);
      exceptionProbe.evaluate(context, exceptionProbe.createStatus(), MethodLocation.EXIT);
    }
    assertEquals(0, exceptionProbeManager.getProbes().size());
    assertWithTimeout(configApplied::get, Duration.ofSeconds(30));
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
