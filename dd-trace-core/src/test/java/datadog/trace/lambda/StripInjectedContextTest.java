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
  void removesTopLevelDatadogKey() {
    // _datadog appearing directly as a top-level key is removed regardless of event shape.
    byte[] input = bytes("{\"detail-type\":\"order.created\",\"_datadog\":{\"trace\":\"1\"}}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog at the top level must be stripped");
    assertTrue(json.contains("detail-type"), "unrelated top-level fields must survive");
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
    // e.g. detail is a number — nothing to strip from, so the fast path returns unchanged.
    byte[] input = bytes("{\"detail\":42}");

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
  // Non-EventBridge payloads: direct top-level _datadog key (SQS, Kinesis, etc.)
  // ============================================================================

  @Test
  void removesTopLevelDatadogKeyFromSqsStylePayload() {
    // SQS-style: _datadog appears as a direct top-level property alongside business data.
    byte[] input =
        bytes("{\"orderId\":\"abc-123\",\"amount\":99,"
            + "\"_datadog\":{\"x-datadog-trace-id\":\"456\",\"x-datadog-parent-id\":\"789\"}}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog carrier must be removed");
    assertTrue(json.contains("\"orderId\":\"abc-123\""), "orderId must survive untouched");
    assertTrue(json.contains("\"amount\":99"), "amount must survive untouched");
  }

  @Test
  void returnsOriginalWhenTopLevelDatadogKeyIsAbsent() {
    // No _datadog anywhere: fast path should return the original byte array unchanged.
    byte[] input = bytes("{\"orderId\":\"abc-123\",\"amount\":99}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  // ============================================================================
  // Non-EventBridge payloads: string-encoded JSON fields (SNS, etc.)
  // ============================================================================

  @Test
  void removesDatadogKeyFromStringEncodedNonDetailField() {
    // SNS-style: the "Message" field is a string-encoded JSON object containing _datadog.
    byte[] input =
        bytes(
            "{\"Type\":\"Notification\","
                + "\"Message\":\"{\\\"hello\\\":\\\"world\\\","
                + "\\\"_datadog\\\":{\\\"x-datadog-trace-id\\\":\\\"123\\\"}}\","
                + "\"Subject\":\"test-event\"}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be stripped from string-encoded Message");
    assertTrue(json.contains("hello"), "business data inside Message must survive");
    assertTrue(json.contains("Subject"), "other top-level fields must survive");
  }

  @Test
  void returnsOriginalWhenStringEncodedFieldContainsNoDatadogKey() {
    // A string-encoded JSON field that doesn't carry _datadog must be left completely unchanged.
    byte[] input =
        bytes("{\"Type\":\"Notification\",\"Message\":\"{\\\"hello\\\":\\\"world\\\"}\"}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  @Test
  void handlesMultipleTopLevelFieldsWithDatadogCarrier() {
    // Both a direct top-level _datadog key and a string-encoded field containing _datadog
    // are present at the same time; both must be stripped.
    byte[] input =
        bytes(
            "{\"_datadog\":{\"trace\":\"1\"},"
                + "\"Message\":\"{\\\"key\\\":\\\"val\\\","
                + "\\\"_datadog\\\":{\\\"trace\\\":\\\"2\\\"}}\"}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "all _datadog carriers must be stripped");
    assertTrue(json.contains("key"), "business data inside Message must survive");
  }

  // ============================================================================
  // Byte-level splice edge cases: comma placement around the removed carrier
  // ============================================================================

  @Test
  void removesDatadogWhenItIsTheSoleField() {
    // carrierRemovalRange: no preceding comma, no following comma -> remove key+value only.
    // Result must be a valid empty object, not a dangling comma.
    byte[] input = bytes("{\"_datadog\":{\"trace\":\"1\"}}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be removed");
    assertTrue(json.equals("{}"), "result must be a valid empty object, got: " + json);
  }

  @Test
  void removesDatadogWhenItIsTheFirstField() {
    // carrierRemovalRange: no preceding comma but a following comma exists -> remove trailing comma.
    // If the trailing comma is left in, the result is invalid JSON: {"other":"val"} vs {,"other":"val"}.
    byte[] input = bytes("{\"_datadog\":{\"trace\":\"1\"},\"other\":\"val\"}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be removed");
    assertTrue(json.contains("\"other\":\"val\""), "following field must survive");
    assertTrue(!json.startsWith("{,"), "result must not start with a dangling comma");
  }

  @Test
  void removesDatadogWhenItIsTheMiddleField() {
    // carrierRemovalRange: both a preceding comma and a following comma exist.
    // The preceding comma is consumed (preferred), so the remaining fields stay adjacent.
    byte[] input =
        bytes("{\"first\":\"a\",\"_datadog\":{\"trace\":\"1\"},\"last\":\"b\"}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be removed");
    assertTrue(json.contains("\"first\":\"a\""), "first field must survive");
    assertTrue(json.contains("\"last\":\"b\""), "last field must survive");
    assertTrue(!json.contains(",,"), "result must not contain a double comma");
  }

  @Test
  void doesNotRemoveDatadogKeyWhenValueIsNotAnObject() {
    // The byte-level splice only removes "_datadog":{...} (object value form).
    // A string, null, or number value is not a carrier and must be left untouched.
    byte[] input = bytes("{\"_datadog\":\"not-a-carrier\",\"other\":1}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  @Test
  void doesNotMatchDatadogTextEmbeddedInsideAStringValue() {
    // A string value that happens to contain the text "_datadog:" must not trigger removal.
    // isObjectProperty() guards against this by checking the preceding byte is '{' or ','.
    byte[] input = bytes("{\"note\":\"the _datadog: key is for tracing\",\"other\":1}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  @Test
  void handlesWhitespacePaddedJson() {
    // skipWhitespaceForward and skipWhitespaceBackward must tolerate spaces, tabs, and newlines
    // around the key, colon, and value so that pretty-printed payloads are handled correctly.
    byte[] input =
        bytes("{ \"_datadog\": { \"trace\" : \"1\" } , \"other\" : 1 }");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be removed from whitespace-padded JSON");
    assertTrue(json.contains("\"other\""), "unrelated field must survive");
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
