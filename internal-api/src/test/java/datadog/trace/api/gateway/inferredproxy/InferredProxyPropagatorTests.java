package datadog.trace.api.gateway.inferredproxy;

import static datadog.context.Context.root;
import static datadog.trace.api.gateway.inferredproxy.InferredProxyHeaders.fromContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.Context;
import datadog.context.propagation.CarrierVisitor;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("InferredProxyPropagator Tests")
class InferredProxyPropagatorTests { // Kept non-static
  private static final String PROXY_SYSTEM_KEY = "x-dd-proxy-system";
  private static final String PROXY_REQUEST_TIME_MS_KEY = "x-dd-proxy-request-time-ms";
  private static final String PROXY_PATH_KEY = "x-dd-proxy-path";
  private static final String PROXY_HTTP_METHOD_KEY = "x-dd-proxy-httpmethod";
  private static final String PROXY_DOMAIN_NAME_KEY = "x-dd-proxy-domain-name";
  private static final MapVisitor MAP_VISITOR = new MapVisitor();

  static Stream<Arguments> validHeadersProviderForPropagator() {
    Map<String, String> allStandard = new HashMap<>();
    allStandard.put(PROXY_SYSTEM_KEY, "aws-apigw"); // The only currently supported system
    allStandard.put(PROXY_REQUEST_TIME_MS_KEY, "12345");
    allStandard.put(PROXY_PATH_KEY, "/foo");
    allStandard.put(PROXY_HTTP_METHOD_KEY, "GET");
    allStandard.put(PROXY_DOMAIN_NAME_KEY, "api.example.com");

    return Stream.of(
        Arguments.of(
            "all standard headers (aws-apigw)",
            allStandard,
            "aws-apigw",
            "12345",
            "/foo",
            "GET",
            "api.example.com",
            null,
            null));
  }

  static Stream<Arguments> invalidOrMissingHeadersProviderForPropagator() { // Renamed
    Map<String, String> missingSystem = new HashMap<>();
    missingSystem.put(PROXY_REQUEST_TIME_MS_KEY, "12345");
    missingSystem.put(PROXY_PATH_KEY, "/foo");

    Map<String, String> missingTime = new HashMap<>();
    missingTime.put(PROXY_SYSTEM_KEY, "aws-apigw");
    missingTime.put(PROXY_PATH_KEY, "/foo");

    return Stream.of(
        Arguments.of("PROXY_SYSTEM_KEY missing", missingSystem),
        Arguments.of("PROXY_REQUEST_TIME_MS_KEY missing", missingTime));
  }

  private InferredProxyPropagator propagator;

  @BeforeEach
  void setUp() {
    propagator = new InferredProxyPropagator();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("validHeadersProviderForPropagator")
  @DisplayName("Should extract InferredProxyContext when valid headers are present")
  void testSuccessfulExtraction(
      String description,
      Map<String, String> headers,
      String expectedSystem,
      String expectedTimeMs,
      String expectedPath,
      String expectedMethod,
      String expectedDomain,
      String expectedExtraKey,
      String expectedExtraValue) {

    // Now accesses the outer class's propagator instance field
    Context extractedOuterContext = this.propagator.extract(root(), headers, MAP_VISITOR);
    InferredProxyHeaders inferredProxyHeaders = fromContext(extractedOuterContext);

    assertNotNull(
        inferredProxyHeaders, "InferredProxyContext should not be null for: " + description);
    assertEquals(expectedSystem, inferredProxyHeaders.getValue(PROXY_SYSTEM_KEY));
    assertEquals(expectedTimeMs, inferredProxyHeaders.getValue(PROXY_REQUEST_TIME_MS_KEY));
    assertEquals(expectedPath, inferredProxyHeaders.getValue(PROXY_PATH_KEY));
    assertEquals(expectedMethod, inferredProxyHeaders.getValue(PROXY_HTTP_METHOD_KEY));
    assertEquals(expectedDomain, inferredProxyHeaders.getValue(PROXY_DOMAIN_NAME_KEY));
    if (expectedExtraKey != null) {
      assertEquals(expectedExtraValue, inferredProxyHeaders.getValue(expectedExtraKey));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidOrMissingHeadersProviderForPropagator")
  @DisplayName("Should create InferredProxyContext even if some critical headers are missing")
  void testExtractionWithMissingCriticalHeaders(String description, Map<String, String> headers) {
    Context rootContext = root();
    Context extractedOuterContext = this.propagator.extract(rootContext, headers, MAP_VISITOR);
    InferredProxyHeaders inferredProxyHeaders = fromContext(extractedOuterContext);

    assertNotNull(
        inferredProxyHeaders,
        "InferredProxyContext should still be created if any x-dd-proxy-* header is present for: "
            + description);

    if (headers.containsKey(PROXY_SYSTEM_KEY)) {
      assertEquals(headers.get(PROXY_SYSTEM_KEY), inferredProxyHeaders.getValue(PROXY_SYSTEM_KEY));
    } else {
      assertNull(inferredProxyHeaders.getValue(PROXY_SYSTEM_KEY));
    }
    if (headers.containsKey(PROXY_REQUEST_TIME_MS_KEY)) {
      assertEquals(
          headers.get(PROXY_REQUEST_TIME_MS_KEY),
          inferredProxyHeaders.getValue(PROXY_REQUEST_TIME_MS_KEY));
    } else {
      assertNull(inferredProxyHeaders.getValue(PROXY_REQUEST_TIME_MS_KEY));
    }
  }

  @Test
  @DisplayName("Should not extract InferredProxyContext if no relevant headers are present")
  void testNoRelevantHeaders() {
    Map<String, String> carrier = new HashMap<>();
    carrier.put("x-unrelated-header", "value");
    carrier.put("another-header", "othervalue");

    Context extractedOuterContext = this.propagator.extract(root(), carrier, MAP_VISITOR);
    InferredProxyHeaders inferredProxyHeaders = fromContext(extractedOuterContext);

    assertNull(
        inferredProxyHeaders,
        "InferredProxyContext should be null if no x-dd-proxy-* headers are found");
  }

  @Test
  @DisplayName("Extractor should handle multiple proxy headers")
  void testMultipleProxyHeaders() {
    Map<String, String> carrier = new HashMap<>();
    carrier.put(PROXY_SYSTEM_KEY, "aws-apigw");
    carrier.put(PROXY_REQUEST_TIME_MS_KEY, "12345");
    carrier.put("x-dd-proxy-custom", "value1"); // First proxy header
    carrier.put("x-dd-proxy-another", "value2"); // Second proxy header

    Context extractedOuterContext = this.propagator.extract(root(), carrier, MAP_VISITOR);
    InferredProxyHeaders inferredProxyHeaders = fromContext(extractedOuterContext);

    assertNotNull(inferredProxyHeaders);
    // Check if both headers were stored (covers extractedContext == null being false)
    assertEquals("value1", inferredProxyHeaders.getValue("x-dd-proxy-custom"));
    assertEquals("value2", inferredProxyHeaders.getValue("x-dd-proxy-another"));
    assertEquals("aws-apigw", inferredProxyHeaders.getValue(PROXY_SYSTEM_KEY));
  }

  @Test
  @DisplayName("Extractor accept method should handle null/empty keys")
  void testExtractorAcceptNullEmptyKeys() {

    // Test null key - HashMap doesn't allow null keys. Standard HTTP visitors
    // also typically don't yield null keys. Testing this branch is difficult
    // without a custom visitor or modifying the source. Relying on coverage report
    // or assuming standard carriers won't provide null keys.

    // Test empty key
    Map<String, String> carrierWithEmptyKey = new HashMap<>();
    carrierWithEmptyKey.put("", "emptyKeyValue"); // Add empty key
    carrierWithEmptyKey.put(PROXY_SYSTEM_KEY, "aws-apigw"); // Add a valid key too

    Context contextAfterEmpty = this.propagator.extract(root(), carrierWithEmptyKey, MAP_VISITOR);
    InferredProxyHeaders inferredProxyHeaders = fromContext(contextAfterEmpty);

    // The propagator should ignore the empty key entry entirely.
    assertNotNull(inferredProxyHeaders, "Context should be created due to valid key");
    assertNull(inferredProxyHeaders.getValue(""), "Empty key should not be stored");
    assertEquals(
        "aws-apigw",
        inferredProxyHeaders.getValue(PROXY_SYSTEM_KEY),
        "Valid key should still be stored");
    assertEquals(1, inferredProxyHeaders.size(), "Only valid key should be stored");
  }

  // Simple Map visitor for tests (can remain static or non-static in outer class)
  @ParametersAreNonnullByDefault
  private static class MapVisitor implements CarrierVisitor<Map<String, String>> {
    @Override
    public void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> visitor) {
      if (carrier == null) {
        return;
      }
      carrier.forEach(visitor);
    }
  }
}
