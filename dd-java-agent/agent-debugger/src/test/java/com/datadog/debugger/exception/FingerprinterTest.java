package com.datadog.debugger.exception;

import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.util.ClassNameFiltering;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FingerprinterTest {

  final Throwable TEST_THROWABLE = new RuntimeException("test");

  {
    TEST_THROWABLE.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "com.datadog.debugger.exception.FingerprinterTest",
              "<init>",
              "FingerprinterTest.java",
              10),
          new StackTraceElement(
              "org.junit.platform.commons.util.ReflectionUtils",
              "newInstance",
              "ReflectionUtils.java",
              552),
          new StackTraceElement(
              "org.junit.jupiter.engine.execution.ConstructorInvocation",
              "proceed",
              "ConstructorInvocation.java",
              56),
          new StackTraceElement(
              "org.junit.jupiter.engine.execution.InvocationInterceptorChain$ValidatingInvocation",
              "proceed",
              "InvocationInterceptorChain.java",
              131),
        });
  }

  final String TEST_FINGERPRINT = "2ec0db28f254ffa383cbb26a32269bf739ba937b9dd8f111d22294e6a494855";
  final ClassNameFiltering classNameFiltering = new ClassNameFiltering(emptySet());

  @Test
  void basic() {
    String fingerprint = Fingerprinter.fingerprint(TEST_THROWABLE, classNameFiltering);
    assertEquals(TEST_FINGERPRINT, fingerprint);
  }

  @Test
  void inner() {
    Throwable t = new RuntimeException("outer", TEST_THROWABLE);
    String fingerprint = Fingerprinter.fingerprint(t, classNameFiltering);
    assertEquals(TEST_FINGERPRINT, fingerprint);
  }

  @Test
  void innerInfiniteLoop() {
    Exception outer = new RuntimeException("outer");
    Exception innerCause1 = new RuntimeException("cause1", outer);
    Exception innerCause2 = new RuntimeException("cause2", innerCause1);
    outer.initCause(innerCause2);
    Assertions.assertNull(Fingerprinter.fingerprint(outer, classNameFiltering));
  }

  @Test
  void emptyStacktrace() {
    assertEquals(
        "843ff84fcbdc76707588c035f63b0e69b6f9b2c53f9a019ef4e5d1a2243778",
        Fingerprinter.fingerprint(new EmptyException("test"), classNameFiltering));
  }

  static class EmptyException extends Exception {
    public EmptyException(String message) {
      super(message, null, false, false);
    }
  }
}
