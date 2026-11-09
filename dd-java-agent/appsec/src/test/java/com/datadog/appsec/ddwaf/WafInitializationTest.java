package com.datadog.appsec.ddwaf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.FileNotFoundException;
import java.util.Scanner;
import org.junit.jupiter.api.Test;

public class WafInitializationTest {
  @Test
  void deniedLibcDetectionReturnsUnknown() {
    assertEquals(
        "unknown",
        WafInitialization.getLibc(
            () -> "Linux",
            () -> {
              throw new SecurityException("Libc detection denied");
            }));
  }

  @Test
  void missingMapsFileReturnsUnknown() {
    assertEquals(
        "unknown",
        WafInitialization.getLibc(
            () -> "Linux",
            () -> {
              throw new FileNotFoundException("Libc maps file missing");
            }));
  }

  @Test
  void libcDetectionErrorReturnsUnknown() {
    assertEquals(
        "unknown",
        WafInitialization.getLibc(
            () -> "Linux",
            () -> {
              throw new ExceptionInInitializerError("Scanner initialization failed");
            }));
  }

  @Test
  void osLookupErrorReturnsUnknown() {
    assertEquals(
        "unknown",
        WafInitialization.getLibc(
            () -> {
              throw new LinkageError("OS lookup failed");
            },
            () -> new Scanner("")));
  }

  @Test
  void detectsMusl() {
    assertEquals(
        "musl",
        WafInitialization.getLibc(
            () -> "Linux",
            () ->
                new Scanner("7f000000-7f001000 r-xp 00000000 00:00 0 /lib/ld-musl-x86_64.so.1\n")));
  }
}
