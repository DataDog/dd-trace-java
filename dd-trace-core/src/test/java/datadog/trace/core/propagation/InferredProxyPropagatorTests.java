package datadog.trace.core.propagation;

import static datadog.context.Context.root;
import static datadog.trace.api.gateway.InferredProxySpan.fromContext;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.of;

import datadog.context.Context;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.api.gateway.InferredProxySpan;
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
class InferredProxyPropagatorTests {
  private static final String PROXY_SYSTEM_KEY = "x-dd-proxy";
  private static final String PROXY_REQUEST_TIME_MS_KEY = "x-dd-proxy-request-time-ms";
  private static final String PROXY_PATH_KEY = "x-dd-proxy-path";
  private static final String PROXY_HTTP_METHOD_KEY = "x-dd-proxy-httpmethod";
  private static final String PROXY_DOMAIN_NAME_KEY = "x-dd-proxy-domain-name";
  private static final MapVisitor MAP_VISITOR = new MapVisitor();

  private InferredProxyPropagator propagator;

  @BeforeEach
  void setUp() {
    this.propagator = new InferredProxyPropagator();
  }

  @Test
  @DisplayName("Should extract InferredProxySpan when valid headers are present")
  void testSuccessfulExtraction() {
    Map<String, String> headers = new HashMap<>();
    headers.put(PROXY_SYSTEM_KEY, "aws-apigateway");
    headers.put(PROXY_REQUEST_TIME_MS_KEY, "12345");
    headers.put(PROXY_PATH_KEY, "/foo");
    headers.put(PROXY_HTTP_METHOD_KEY, "GET");
    headers.put(PROXY_DOMAIN_NAME_KEY, "api.example.com");

    Context context = this.propagator.extract(root(), headers, MAP_VISITOR);
    InferredProxySpan inferredProxySpan = fromContext(context);
    assertNotNull(inferredProxySpan);
    assertTrue(inferredProxySpan.isValid());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidOrMissingHeadersProviderForPropagator")
  @DisplayName("Should not create InferredProxySpan if some critical headers are missing")
  void testExtractionWithMissingCriticalHeaders(String description, Map<String, String> headers) {
    Context rootContext = root();
    Context extractedOuterContext = this.propagator.extract(rootContext, headers, MAP_VISITOR);
    InferredProxySpan inferredProxySpan = fromContext(extractedOuterContext);
    assertNull(inferredProxySpan, "Invalid inferred proxy span should not be extracted");
  }

  static Stream<Arguments> invalidOrMissingHeadersProviderForPropagator() { // Renamed
    Map<String, String> missingSystem = new HashMap<>();
    missingSystem.put(PROXY_REQUEST_TIME_MS_KEY, "12345");
    missingSystem.put(PROXY_PATH_KEY, "/foo");

    Map<String, String> emptyValue = new HashMap<>();
    emptyValue.put(PROXY_SYSTEM_KEY, "");

    Map<String, String> nullValue = new HashMap<>();
    nullValue.put(PROXY_SYSTEM_KEY, null);

    Map<String, String> missingTime = new HashMap<>();
    missingTime.put(PROXY_SYSTEM_KEY, "aws-apigw");
    missingTime.put(PROXY_PATH_KEY, "/foo");

    return Stream.of(
        of("PROXY_SYSTEM_KEY missing", missingSystem),
        of("PROXY_SYSTEM_KEY empty", emptyValue),
        of("PROXY_SYSTEM_KEY null", nullValue),
        of("PROXY_REQUEST_TIME_MS_KEY missing", missingTime));
  }

  @ParametersAreNonnullByDefault
  private static class MapVisitor implements CarrierVisitor<Map<String, String>> {
    @Override
    public void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> visitor) {
      carrier.forEach(visitor);
    }
  }
}
