package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FingerprinterTest {

  final Throwable TEST_THROWABLE = new RuntimeException("test");
  final String TEST_FINGERPRINT = "ff3e1b608464f0d0908f870e4fd7558dbdcab2c77e4592d8fffcdb778fc06d";

  @Test
  void basic() {
    String fingerprint = Fingerprinter.fingerprint(TEST_THROWABLE);
    assertEquals(TEST_FINGERPRINT, fingerprint);
  }

  @Test
  void inner() {
    Throwable t = new RuntimeException("outer", TEST_THROWABLE);
    String fingerprint = Fingerprinter.fingerprint(t);
    assertEquals(TEST_FINGERPRINT, fingerprint);
  }

  @Test
  void innerInfiniteLoop() {
    Exception outer = new RuntimeException("outer");
    Exception innerCause1 = new RuntimeException("cause1", outer);
    Exception innerCause2 = new RuntimeException("cause2", innerCause1);
    outer.initCause(innerCause2);
    Assertions.assertNull(Fingerprinter.fingerprint(outer));
  }

  @Test
  void emptyStacktrace() {
    assertEquals(
        "843ff84fcbdc76707588c035f63b0e69b6f9b2c53f9a019ef4e5d1a2243778",
        Fingerprinter.fingerprint(new EmptyException("test")));
  }

  static class EmptyException extends Exception {
    public EmptyException(String message) {
      super(message, null, false, false);
    }
  }
}
