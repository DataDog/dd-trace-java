package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OtelHttpMethodsTest {

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "QUERY"})
  void knownMethodsAreRecognized(final String method) {
    assertTrue(OtelHttpMethods.isKnown(method));
    assertEquals(method, OtelHttpMethods.spanName(method));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROPFIND", "get", "Get", "BOGUS"})
  void unknownMethodsUseHttpSpanName(final String method) {
    assertFalse(OtelHttpMethods.isKnown(method));
    assertEquals("HTTP", OtelHttpMethods.spanName(method));
  }

  @Test
  void nullMethodIsUnknownAndUsesHttp() {
    assertFalse(OtelHttpMethods.isKnown(null));
    assertEquals("HTTP", OtelHttpMethods.spanName(null));
  }
}
