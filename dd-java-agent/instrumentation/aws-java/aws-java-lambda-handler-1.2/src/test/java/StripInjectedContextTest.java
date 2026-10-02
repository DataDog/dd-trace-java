package datadog.trace.instrumentation.aws.v1.lambda;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
        bytes(
            "{\"orderId\":\"abc-123\",\"amount\":99,"
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
    // carrierRange: no preceding comma, no following comma -> remove key+value only.
    // Result must be a valid empty object, not a dangling comma.
    byte[] input = bytes("{\"_datadog\":{\"trace\":\"1\"}}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be removed");
    assertTrue(json.equals("{}"), "result must be a valid empty object, got: " + json);
  }

  @Test
  void removesDatadogWhenItIsTheFirstField() {
    // carrierRange: no preceding comma but a following comma exists -> remove trailing
    // comma.
    // If the trailing comma is left in, the result is invalid JSON: {"other":"val"} vs
    // {,"other":"val"}.
    byte[] input = bytes("{\"_datadog\":{\"trace\":\"1\"},\"other\":\"val\"}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be removed");
    assertTrue(json.contains("\"other\":\"val\""), "following field must survive");
    assertTrue(!json.startsWith("{,"), "result must not start with a dangling comma");
  }

  @Test
  void removesDatadogWhenItIsTheMiddleField() {
    // carrierRange: both a preceding comma and a following comma exist.
    // The preceding comma is consumed (preferred), so the remaining fields stay adjacent.
    byte[] input = bytes("{\"first\":\"a\",\"_datadog\":{\"trace\":\"1\"},\"last\":\"b\"}");

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
    // String tokens are skipped as a whole, so text inside a value is never treated as a key.
    byte[] input = bytes("{\"note\":\"the _datadog: key is for tracing\",\"other\":1}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result);
  }

  @Test
  void handlesWhitespacePaddedJson() {
    // skipWhitespace and skipWhitespaceBackward must tolerate spaces, tabs, and newlines
    // around the key, colon, and value so that pretty-printed payloads are handled correctly.
    byte[] input = bytes("{ \"_datadog\": { \"trace\" : \"1\" } , \"other\" : 1 }");

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

  @Test
  void doesNotRemoveNonObjectDatadogValueInsideStringEncodedField() {
    // Regression test for review feedback: a string-encoded business payload (e.g. SQS body)
    // whose "_datadog" field holds a plain string, not an object, must survive untouched.
    // Only object-shaped propagation carriers ("_datadog":{...}) are carriers; anything else
    // is customer data that happens to reuse the same key name.
    byte[] input =
        bytes(
            "{\"Records\":[{\"body\":\"{\\\"_datadog\\\":\\\"customer-value\\\","
                + "\\\"order\\\":\\\"foo\\\"}\"}]}");

    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(
        input, result, "non-object _datadog value inside a string-encoded field must be preserved");
  }

  @Test
  void removesDatadogFromNestedSqsRecordsBody() {
    // SQS Records[*].body is itself a string-encoded JSON object.
    byte[] input =
        bytes(
            "{\"Records\":[{\"body\":\"{\\\"orderId\\\":1,"
                + "\\\"_datadog\\\":{\\\"trace\\\":\\\"1\\\"}}\"}]}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog inside Records[].body must be stripped");
    assertTrue(json.contains("orderId"));
  }

  @Test
  void removesDatadogFromDoubleEncodedSnsInsideSqsBody() {
    // SQS body containing an SNS envelope whose Message is itself string-encoded JSON.
    String snsMessage =
        "{\\\\\\\"orderId\\\\\\\":1,\\\\\\\"_datadog\\\\\\\":"
            + "{\\\\\\\"trace\\\\\\\":\\\\\\\"1\\\\\\\"}}";
    byte[] input =
        bytes("{\"Records\":[{\"body\":\"{\\\"Message\\\":\\\"" + snsMessage + "\\\"}\"}]}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "double-encoded _datadog must be stripped");
    assertTrue(json.contains("orderId"));
  }

  @Test
  void removesObjectCarrierWithWhitespaceBeforeColon() {
    byte[] input = bytes("{\"_datadog\" : {\"trace\":\"1\"},\"other\":1}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "whitespace before colon must not block removal");
    assertTrue(json.contains("\"other\":1"));
  }

  @Test
  void doesNotRecurseBeyondMaxDepth() {
    // The carrier is wrapped one level deeper than MAX_DEPTH allows. The final layer is never
    // decoded,
    // so no plain "_datadog" object key is ever seen.
    int wraps = StripInjectedContext.MAX_DEPTH + 1;
    String payload = "{\"orderId\":1,\"_datadog\":{\"trace\":\"1\"}}";
    for (int i = 0; i < wraps; i++) {
      payload = "{\"detail\":" + jsonQuote(payload) + "}";
    }

    byte[] input = bytes(payload);
    byte[] result = StripInjectedContext.stripInternal(input);

    assertArrayEquals(input, result, "carrier nested beyond MAX_DEPTH must be returned unchanged");
  }

  /** Encodes {@code raw} as a JSON string literal (adds surrounding quotes and escapes). */
  private static String jsonQuote(String raw) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.append('"').toString();
  }

  @Test
  void stripsCarrierNestedExactlyAtMaxDepth() {
    int wraps = StripInjectedContext.MAX_DEPTH;
    String payload = "{\"orderId\":1,\"_datadog\":{\"trace\":\"1\"}}";
    for (int i = 0; i < wraps; i++) {
      payload = "{\"detail\":" + jsonQuote(payload) + "}";
    }

    byte[] result = StripInjectedContext.stripInternal(bytes(payload));
    String json = toUtf8(result);

    assertTrue(
        !json.contains("_datadog"), "carrier nested exactly at MAX_DEPTH must still be stripped");
    assertTrue(json.contains("orderId"));
  }

  @Test
  void preservesSurrogatePairEmojiInStringEncodedBusinessData() {
    // Business text containing an astral-plane character encoded as a surrogate pair of unicode
    // escapes (e.g. an emoji) must survive when it sits in the same string-encoded field as a
    // genuine _datadog carrier that gets stripped.
    byte[] input =
        bytes(
            "{\"Message\":\"{\\\"note\\\":\\\"Thanks \\uD83D\\uDE00\\\","
                + "\\\"_datadog\\\":{\\\"trace\\\":\\\"1\\\"}}\"}");

    byte[] result = StripInjectedContext.stripInternal(input);
    String json = toUtf8(result);

    assertTrue(!json.contains("_datadog"), "_datadog must be stripped");
    assertTrue(
        json.contains("Thanks \\uD83D\\uDE00"),
        "surrogate-pair emoji in business data must survive intact");
  }

  @Test
  void doesNotThrowOnSignedHexInUnicodeEscape() {
    byte[] input = bytes("{\"Message\":\"{\\\"n\\\":\\\"\\u-001\\\",\\\"_datadog\\\":{}}\"}");

    String json = toUtf8(StripInjectedContext.stripInternal(input));

    assertTrue(!json.contains("_datadog"), "_datadog must be stripped");
  }

  @Test
  void preservesLoneSurrogateWhileStrippingCarrier() {
    byte[] input = bytes("{\"Message\":\"{\\\"n\\\":\\\"x\\uD83D y\\\",\\\"_datadog\\\":{}}\"}");

    String json = toUtf8(StripInjectedContext.stripInternal(input));

    assertTrue(!json.contains("_datadog"), "_datadog must be stripped");
    assertTrue(json.contains("x\\uD83D y"), "a lone surrogate must not be turned into '?'");
  }

  @Test
  void unclosedCarriersDoNotCauseQuadraticScan() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < 20_000; i++) {
      body.append("{\\\"_datadog\\\":{");
    }
    byte[] input = bytes("{\"Records\":[{\"body\":\"" + body + "\"}]}");

    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> assertArrayEquals(input, StripInjectedContext.stripInternal(input)));
  }
}
