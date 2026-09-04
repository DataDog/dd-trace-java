package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StripInjectedContext}. */
class StripInjectedContextTest extends DDJavaSpecification {

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String toUtf8(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static byte[] readAll(ByteArrayInputStream in) {
    in.mark(Integer.MAX_VALUE);
    byte[] bytes = new byte[in.available()];
    int read = in.read(bytes, 0, bytes.length);
    in.reset();
    return read == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, Math.max(read, 0));
  }

  // ============================================================================
  // stripInternal: detail as an object
  // ============================================================================

  @Test
  void removesDatadogKeyFromObjectDetail() {
    byte[] input =
        bytes(
            "{\"detail-type\":\"order.created\",\"detail\":{\"orderId\":42,"
                + "\"_datadog\":{\"x-datadog-trace-id\":\"123\"}}}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(json.contains("\"orderId\":42"), "unrelated fields must survive untouched");
    assertTrue(!json.contains("_datadog"), "the _datadog carrier must be removed");
  }

  // ============================================================================
  // stripInternal: detail as a string-encoded JSON object
  // ============================================================================

  @Test
  void removesDatadogKeyFromStringEncodedDetail() {
    // "detail" itself is a JSON string containing an escaped JSON object
    byte[] input =
        bytes(
            "{\"detail\":\"{\\\"orderId\\\":42,\\\"_datadog\\\":{\\\"x-datadog-trace-id\\\":\\\"123\\\"}}\"}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"));
    assertTrue(json.contains("orderId"));
  }

  // ============================================================================
  // Fail-open cases: original payload must be returned unchanged
  // ============================================================================

  @Test
  void returnsOriginalWhenDatadogKeyIsAbsent() {
    byte[] input = bytes("{\"detail\":{\"orderId\":42}}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  @Test
  void returnsOriginalWhenDetailFieldIsMissing() {
    byte[] input = bytes("{\"detail-type\":\"order.created\",\"_datadog\":{\"trace\":\"1\"}}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  @Test
  void returnsOriginalOnMalformedJson() {
    byte[] input = bytes("{not valid json, but contains _datadog anyway");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  @Test
  void returnsOriginalForNullOrEmptyPayload() {
    assertNull(StripInjectedContext.stripInternal(null));
    assertArrayEquals(new byte[0], StripInjectedContext.stripInternal(new byte[0]));
  }

  @Test
  void returnsOriginalWhenDetailIsNeitherObjectNorString() {
    // e.g. detail is a number/array/null — nothing sane to strip from
    byte[] input = bytes("{\"detail\":42,\"_datadog\":{\"trace\":\"1\"}}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  // ============================================================================
  // Number type preservation (the whole reason for the custom Moshi adapter)
  // ============================================================================

  @Test
  void preservesWholeNumbersAsIntegersAfterStripping() {
    byte[] input =
        bytes("{\"detail\":{\"orderId\":42,\"quantity\":7," + "\"_datadog\":{\"trace\":\"1\"}}}");

    String json = toUtf8(StripInjectedContext.stripInternal(input));

    // Without the custom adapter, Moshi's default would turn these into "42.0"/"7.0"
    assertTrue(json.contains("\"orderId\":42"));
    assertTrue(json.contains("\"quantity\":7"));
    assertTrue(!json.contains(".0"));
  }

  @Test
  void preservesFractionalNumbersAsDoublesAfterStripping() {
    byte[] input = bytes("{\"detail\":{\"price\":19.99,\"_datadog\":{\"trace\":\"1\"}}}");

    String json = toUtf8(StripInjectedContext.stripInternal(input));

    assertTrue(json.contains("\"price\":19.99"));
  }

  @Test
  void preservesLargeIntegersBeyondIntRangeAsLong() {
    byte[] input =
        bytes("{\"detail\":{\"bigId\":9007199254740993,\"_datadog\":{\"trace\":\"1\"}}}");

    String json = toUtf8(StripInjectedContext.stripInternal(input));

    // A double could not represent this value exactly; Long must be used instead.
    assertTrue(json.contains("\"bigId\":9007199254740993"));
  }

  // ============================================================================
  // strip(): the public entry point that is gated by config
  // ============================================================================

  @Test
  void stripReturnsOriginalPayloadWhenConfigIsDisabledByDefault() {
    byte[] input = bytes("{\"detail\":{\"_datadog\":{\"trace\":\"1\"}}}");

    byte[] result = StripInjectedContext.strip(input);

    assertArrayEquals(input, result);
  }

  @Test
  @WithConfig(key = GeneralConfig.LAMBDA_STRIP_INJECTED_CONTEXT, value = "true")
  void stripRemovesDatadogKeyWhenConfigIsEnabled() {
    byte[] input = bytes("{\"detail\":{\"orderId\":1,\"_datadog\":{\"trace\":\"1\"}}}");

    byte[] result = StripInjectedContext.strip(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"));
  }

  // ============================================================================
  // replaceInputStream(): the InputStream-based helper used by the advice
  // ============================================================================

  @Test
  void replaceInputStreamReturnsNullForNullInput() {
    assertNull(StripInjectedContext.replaceInputStream(null));
  }

  @Test
  @WithConfig(key = GeneralConfig.LAMBDA_STRIP_INJECTED_CONTEXT, value = "true")
  void replaceInputStreamStripsAndDoesNotConsumeTheOriginalStream() {
    String eventJson = "{\"detail\":{\"orderId\":1,\"_datadog\":{\"trace\":\"1\"}}}";
    ByteArrayInputStream original =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));

    ByteArrayInputStream stripped = StripInjectedContext.replaceInputStream(original);

    assertNotNull(stripped);
    String strippedJson = new String(readAll(stripped), StandardCharsets.UTF_8);
    assertTrue(!strippedJson.contains("_datadog"));

    // readAllBytes() inside replaceInputStream() must mark/reset, not drain, the original stream
    String originalJson = new String(readAll(original), StandardCharsets.UTF_8);
    assertTrue(originalJson.contains("_datadog"), "original stream must still be fully readable");
  }
}
