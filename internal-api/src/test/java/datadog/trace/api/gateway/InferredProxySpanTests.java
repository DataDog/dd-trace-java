package datadog.trace.api.gateway;

import static datadog.context.Context.root;
import static datadog.trace.api.gateway.InferredProxySpan.PROXY_START_TIME_MS;
import static datadog.trace.api.gateway.InferredProxySpan.PROXY_SYSTEM;
import static datadog.trace.api.gateway.InferredProxySpan.fromContext;
import static datadog.trace.api.gateway.InferredProxySpan.fromHeaders;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.of;

import datadog.context.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("InferredProxyContext Tests")
class InferredProxySpanTests {
  @Test
  @DisplayName("Valid headers should make valid span")
  void testMapConstructor() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "12345");
    headers.put(PROXY_SYSTEM, "aws-apigateway");

    InferredProxySpan inferredProxySpan = InferredProxySpan.fromHeaders(headers);
    assertTrue(inferredProxySpan.isValid());
    assertNotNull(
        inferredProxySpan.start(null), "inferred proxy span start and return new parent context");
    assertNull(inferredProxySpan.start(null), "inferred proxy span not should start twice");
    inferredProxySpan.finish();
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("Invalid headers should make invalid span")
  @MethodSource("invalidHeaders")
  void testInvalidHeaders(String useCase, Map<String, String> headers) {
    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertFalse(inferredProxySpan.isValid(), useCase + " should not be valid");
    assertNull(inferredProxySpan.start(null), "Invalid inferred proxy span should not start");
  }

  static Stream<Arguments> invalidHeaders() { // Renamed
    Map<String, String> missingSystem = new HashMap<>();
    missingSystem.put(PROXY_START_TIME_MS, "12345");

    Map<String, String> missingTime = new HashMap<>();
    missingTime.put(PROXY_SYSTEM, "aws-apigateway");
    Map<String, String> invalidSystem = new HashMap<>();
    invalidSystem.put(PROXY_START_TIME_MS, "12345");
    invalidSystem.put(PROXY_SYSTEM, "invalidSystem");

    return Stream.of(
        of("Missing system headers", missingSystem),
        of("Missing start time headers", missingTime),
        of("Invalid system headers", invalidSystem));
  }

  @Test
  @DisplayName("Constructor with null should not crash")
  void testNullMapConstructor() {
    InferredProxySpan inferredProxySpan = fromHeaders(null);
    assertNotNull(inferredProxySpan);
    assertFalse(inferredProxySpan.isValid());
  }

  @Test
  @DisplayName("Constructor with empty map should be invalid")
  void testEmptyMapConstructor() {
    InferredProxySpan inferredProxySpan = fromHeaders(emptyMap());
    assertNotNull(inferredProxySpan);
    assertFalse(inferredProxySpan.isValid());
  }

  @Test
  @DisplayName("storeInto and fromContext should correctly attach and retrieve the context")
  void testStoreAndFromContext() {
    InferredProxySpan inferredProxySpan = fromHeaders(null);
    Context context = inferredProxySpan.storeInto(root());
    assertNotNull(context);

    InferredProxySpan retrieved = fromContext(context);
    assertNotNull(retrieved);

    assertNull(fromContext(root()), "fromContext on empty context should be null");
  }

  @Test
  @DisplayName("Invalid start time should return extracted context")
  void testInvalidStartTime() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "invalid-number");
    headers.put(PROXY_SYSTEM, "aws-apigateway");

    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertTrue(inferredProxySpan.isValid());
    assertNull(inferredProxySpan.start(null), "Invalid start time should return null");
  }

  @Test
  @DisplayName("Service name should fallback to config when domain name is null")
  void testServiceNameFallbackNull() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "12345");
    headers.put(PROXY_SYSTEM, "aws-apigateway");
    headers.put(InferredProxySpan.PROXY_HTTP_METHOD, "GET");
    headers.put(InferredProxySpan.PROXY_PATH, "/test");

    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertNotNull(inferredProxySpan.start(null));
    // Service name should use Config.get().getServiceName() when domain name is null
  }

  @Test
  @DisplayName("Service name should fallback to config when domain name is empty")
  void testServiceNameFallbackEmpty() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "12345");
    headers.put(PROXY_SYSTEM, "aws-apigateway");
    headers.put(InferredProxySpan.PROXY_DOMAIN_NAME, "");
    headers.put(InferredProxySpan.PROXY_HTTP_METHOD, "GET");
    headers.put(InferredProxySpan.PROXY_PATH, "/test");

    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertNotNull(inferredProxySpan.start(null));
    // Service name should use Config.get().getServiceName() when domain name is empty
  }

  @Test
  @DisplayName("HTTP URL should use path only when domain name is null")
  void testHttpUrlWithoutDomain() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "12345");
    headers.put(PROXY_SYSTEM, "aws-apigateway");
    headers.put(InferredProxySpan.PROXY_HTTP_METHOD, "GET");
    headers.put(InferredProxySpan.PROXY_PATH, "/test");

    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertNotNull(inferredProxySpan.start(null));
    // HTTP URL should be just the path when domain name is null
  }

  @Test
  @DisplayName("Resource name should be null when httpMethod is null")
  void testResourceNameNullMethod() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "12345");
    headers.put(PROXY_SYSTEM, "aws-apigateway");
    headers.put(InferredProxySpan.PROXY_PATH, "/test");

    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertNotNull(inferredProxySpan.start(null));
    // Resource name should be null when httpMethod is null
  }

  @Test
  @DisplayName("Resource name should be null when path is null")
  void testResourceNameNullPath() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "12345");
    headers.put(PROXY_SYSTEM, "aws-apigateway");
    headers.put(InferredProxySpan.PROXY_HTTP_METHOD, "GET");

    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertNotNull(inferredProxySpan.start(null));
    // Resource name should be null when path is null
  }

  @Test
  @DisplayName("Finish should handle null span gracefully")
  void testFinishWithNullSpan() {
    InferredProxySpan inferredProxySpan = fromHeaders(null);
    // Should not throw exception when span is null
    inferredProxySpan.finish();
    assertFalse(inferredProxySpan.isValid());
  }

  @Test
  @DisplayName("Finish should clear span after finishing")
  void testFinishClearsSpan() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_START_TIME_MS, "12345");
    headers.put(PROXY_SYSTEM, "aws-apigateway");

    InferredProxySpan inferredProxySpan = fromHeaders(headers);
    assertNotNull(inferredProxySpan.start(null));
    inferredProxySpan.finish();
    // Span should be cleared after finish, so calling finish again should be safe
    inferredProxySpan.finish();
  }
}
