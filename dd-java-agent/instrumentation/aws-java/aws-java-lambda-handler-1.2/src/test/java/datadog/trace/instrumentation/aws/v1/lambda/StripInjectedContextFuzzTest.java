package datadog.trace.instrumentation.aws.v1.lambda;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Randomized invariant testing for {@link StripInjectedContext}, mirroring the repo's existing
 * fuzz-style test pattern (see {@code TagMapFuzzTest}). Generates many random payloads in
 * EventBridge, SQS, and SNS shapes instead of enumerating fixed cases by hand, and asserts core
 * invariants hold for every one of them.
 */
public final class StripInjectedContextFuzzTest {

  private static final long SEED = envLong("FUZZ_SEED", System.nanoTime());
  private static final int ITERATIONS = (int) envLong("FUZZ_ITERATIONS", 5_000);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String[] SEEDS = {
    "{\"detail\":{\"_datadog\":{\"x-datadog-trace-id\":\"1\"},\"foo\":\"bar\"}}",
    "{\"detail\":\"{\\\"foo\\\":\\\"bar\\\",\\\"_datadog\\\":{\\\"k\\\":\\\"v\\\"}}\"}",
    "{\"foo\":\"a,}b\",\"_datadog\":{\"x\":1}}",
    "{\"detail\":\"{\\\"foo\\\":\\\"bar\\\",\\\"_datadog\\\":{\\\"k\\\":\\\"v}z\\\"}}\"}",
    "{\"_datadog\":{},\"_datadog\":{}}",
    "not even json",
    "{}",
    ""
  };

  /** Inputs that once failed. Paste the input from a failure message here so it always runs. */
  private static final String[] REGRESSIONS = {};

  @BeforeAll
  static void printSeed() {
    System.out.println(
        "StripInjectedContextFuzzTest FUZZ_SEED=" + SEED + " FUZZ_ITERATIONS=" + ITERATIONS);
  }

  static Stream<String> corpus() {
    return Stream.concat(Arrays.stream(SEEDS), Arrays.stream(REGRESSIONS));
  }

  @ParameterizedTest
  @MethodSource("corpus")
  void seedCorpusHoldsInvariants(String input) {
    checkInvariants(input, "");
  }

  private static final String[] FRAGMENTS = {
    "{",
    "}",
    "[",
    "]",
    "\"",
    ",",
    ":",
    "\\",
    " ",
    "\\\"",
    "_datadog",
    "\"_datadog\":",
    "\"_datadog\":{}",
    "\\u00e9",
    "1"
  };

  private static final String[] STRING_PARTS = {
    "a", "}", "{", ",", "\\\"", "\\\\", ":", " ", "_datadog", "\\u00e9"
  };

  // A "_datadog" key inside the "other" value would itself be stripped, so keys there avoid it.
  private static final String[] OTHER_KEY_PARTS = {
    "a", "}", "{", ",", "\\\"", "\\\\", ":", " ", "x_datadog", "\\u00e9"
  };

  private static void checkInvariants(String input, String where) {
    byte[] in = input.getBytes(StandardCharsets.UTF_8);
    byte[] out;
    try {
      out = StripInjectedContext.stripInternal(in);
    } catch (Throwable t) {
      fail(where + "stripInternal threw for in=" + input, t);
      return;
    }
    // Malformed input is only guaranteed not to throw: cutting a carrier out of it can splice
    // the surrounding bytes into a new carrier, so it is not necessarily idempotent.
    if (isValidJson(in)) {
      assertTrue(
          isValidJson(out), where + "valid JSON became invalid: in=" + input + " out=" + utf8(out));
      assertArrayEquals(
          out, StripInjectedContext.stripInternal(out), where + "not idempotent: in=" + input);
    }
  }

  private static String where(int iteration) {
    return "[FUZZ_SEED=" + SEED + " iteration=" + iteration + "] ";
  }

  private static long envLong(String name, long defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? defaultValue : Long.parseLong(value);
  }

  @Test
  void neverThrowsAndAlwaysRemovesDatadogWhenPresent() {
    Random random = new Random(SEED);

    for (int i = 0; i < ITERATIONS; i++) {
      byte[] payload = randomPayload(random);
      String input = utf8(payload);

      byte[] result;
      try {
        result = StripInjectedContext.stripInternal(payload);
      } catch (Throwable t) {
        fail(where(i) + "stripInternal threw for in=" + input, t);
        return;
      }

      String resultJson = utf8(result);
      assertFalse(
          resultJson.contains("_datadog"),
          where(i) + "output contains _datadog: in=" + input + " out=" + resultJson);
      assertArrayEquals(
          result,
          StripInjectedContext.stripInternal(result),
          where(i) + "not idempotent: in=" + input);
    }
  }

  @Test
  void mutatedSeedsKeepValidJsonValidAndAreIdempotent() {
    Random random = new Random(SEED);

    for (int i = 0; i < ITERATIONS; i++) {
      checkInvariants(mutate(SEEDS[random.nextInt(SEEDS.length)], random), where(i));
    }
  }

  @Test
  void arbitraryCarrierIsAlwaysRemovedAndOtherFieldSurvives() throws IOException {
    Random random = new Random(SEED);

    for (int i = 0; i < ITERATIONS; i++) {
      String carrier = randomObject(random, 0, STRING_PARTS);
      String other = randomValue(random, 0, OTHER_KEY_PARTS);
      String payload = "{\"_datadog\":" + carrier + ",\"other\":" + other + "}";

      byte[] out = StripInjectedContext.stripInternal(payload.getBytes(StandardCharsets.UTF_8));

      JsonNode result;
      try {
        result = MAPPER.readTree(out);
      } catch (IOException e) {
        fail(where(i) + "output is not valid JSON: in=" + payload + " out=" + utf8(out), e);
        return;
      }
      assertFalse(
          result.has("_datadog"),
          where(i) + "carrier was not removed: in=" + payload + " out=" + utf8(out));
      assertEquals(
          MAPPER.readTree(other), result.get("other"), where(i) + "other changed: in=" + payload);
    }
  }

  private static byte[] randomPayload(Random random) {
    switch (random.nextInt(3)) {
      case 0:
        return randomEventBridgePayload(random);
      case 1:
        return randomTopLevelCarrierPayload(random);
      default:
        return randomStringEncodedFieldPayload(random);
    }
  }

  // EventBridge-shaped: {"detail-type": ..., "detail": {...}} or {"detail": "..."}
  private static byte[] randomEventBridgePayload(Random random) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"detail-type\":\"order.created\",\"detail\":");
    boolean asString = random.nextBoolean();
    boolean includeDatadog = random.nextBoolean();

    String detailJson = randomDetailObject(random, includeDatadog);
    if (asString) {
      sb.append('"').append(detailJson.replace("\"", "\\\"")).append('"');
    } else {
      sb.append(detailJson);
    }
    sb.append('}');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  // SQS/generic-shaped: _datadog appears directly as a top-level key.
  // Randomly places _datadog as the sole field, the first field, a middle field,
  // or the last field so that all four comma-placement cases in carrierRange
  // are exercised across the fuzz iterations.
  private static byte[] randomTopLevelCarrierPayload(Random random) {
    boolean includeDatadog = random.nextBoolean();
    // Allow 0 regular fields so the sole-field case ({"_datadog":{...}}) is also generated.
    int fieldCount = random.nextInt(4);
    // Choose a random insertion position for the _datadog key among the regular fields.
    // Position 0 = first, fieldCount = last, values in between = middle.
    int datadogPos = includeDatadog ? random.nextInt(fieldCount + 1) : fieldCount + 1;

    StringBuilder sb = new StringBuilder("{");
    boolean needsComma = false;
    int regularIdx = 0;

    for (int slot = 0; slot <= fieldCount; slot++) {
      if (slot == datadogPos) {
        // Insert _datadog at the chosen position.
        if (needsComma) sb.append(',');
        sb.append("\"_datadog\":{\"x-datadog-trace-id\":\"")
            .append(random.nextLong())
            .append("\"}");
        needsComma = true;
      }
      if (regularIdx < fieldCount) {
        // Insert one regular field at this slot.
        if (needsComma) sb.append(',');
        sb.append("\"field").append(regularIdx).append("\":");
        appendRandomValue(sb, random);
        needsComma = true;
        regularIdx++;
      }
    }

    sb.append('}');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String randomDetailObject(Random random, boolean includeDatadog) {
    StringBuilder sb = new StringBuilder("{");
    int fieldCount = 1 + random.nextInt(5);
    for (int i = 0; i < fieldCount; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append("field").append(i).append("\":");
      appendRandomValue(sb, random);
    }
    if (includeDatadog) {
      if (fieldCount > 0) {
        sb.append(',');
      }
      sb.append("\"_datadog\":{\"x-datadog-trace-id\":\"").append(random.nextLong()).append("\"}");
    }
    sb.append('}');
    return sb.toString();
  }

  // SNS-shaped: a top-level string field contains a string-encoded JSON object with _datadog.
  private static byte[] randomStringEncodedFieldPayload(Random random) {
    boolean includeDatadog = random.nextBoolean();
    String innerJson = randomDetailObject(random, includeDatadog);
    String escaped = innerJson.replace("\"", "\\\"");
    String payload =
        "{\"Type\":\"Notification\",\"Message\":\"" + escaped + "\",\"Subject\":\"test\"}";
    return payload.getBytes(StandardCharsets.UTF_8);
  }

  private static void appendRandomValue(StringBuilder sb, Random random) {
    switch (random.nextInt(4)) {
      case 0:
        sb.append(random.nextInt());
        break;
      case 1:
        sb.append(random.nextDouble());
        break;
      case 2:
        sb.append('"').append("value").append(random.nextInt(1000)).append('"');
        break;
      default:
        sb.append(random.nextBoolean());
    }
  }

  /** Applies up to three random insert, delete, or replace edits using JSON-significant text. */
  private static String mutate(String seed, Random random) {
    StringBuilder sb = new StringBuilder(seed);
    int edits = random.nextInt(4);
    for (int e = 0; e < edits; e++) {
      int pos = random.nextInt(sb.length() + 1);
      switch (random.nextInt(3)) {
        case 0:
          sb.insert(pos, FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
          break;
        case 1:
          if (pos < sb.length()) {
            sb.deleteCharAt(pos);
          }
          break;
        default:
          if (pos < sb.length()) {
            sb.setCharAt(pos, FRAGMENTS[random.nextInt(FRAGMENTS.length)].charAt(0));
          }
      }
    }
    return sb.toString();
  }

  private static String randomValue(Random random, int depth, String[] keyParts) {
    switch (random.nextInt(depth < 3 ? 7 : 5)) {
      case 0:
        return Long.toString(random.nextLong());
      case 1:
        return random.nextBoolean() ? "true" : "null";
      case 2:
        return randomString(random, STRING_PARTS);
      case 3:
        return "1.50";
      case 4:
        return "9007199254740993";
      case 5:
        return randomObject(random, depth + 1, keyParts);
      default:
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0, n = random.nextInt(3); k < n; k++) {
          sb.append(k > 0 ? "," : "").append(randomValue(random, depth + 1, keyParts));
        }
        return sb.append(']').toString();
    }
  }

  private static String randomObject(Random random, int depth, String[] keyParts) {
    StringBuilder sb = new StringBuilder("{");
    for (int k = 0, n = random.nextInt(4); k < n; k++) {
      sb.append(k > 0 ? "," : "")
          .append(randomString(random, keyParts))
          .append(':')
          .append(randomValue(random, depth + 1, keyParts));
    }
    return sb.append('}').toString();
  }

  private static String randomString(Random random, String[] parts) {
    StringBuilder sb = new StringBuilder("\"");
    for (int k = 0, n = random.nextInt(5); k < n; k++) {
      sb.append(parts[random.nextInt(parts.length)]);
    }
    return sb.append('"').toString();
  }

  /** Strict check: exactly one JSON value with nothing but whitespace after it. */
  private static boolean isValidJson(byte[] json) {
    if (json.length == 0) {
      return false;
    }
    try (JsonParser parser = MAPPER.getFactory().createParser(json)) {
      if (parser.nextToken() == null) {
        return false;
      }
      parser.skipChildren();
      return parser.nextToken() == null;
    } catch (IOException e) {
      return false;
    }
  }

  private static String utf8(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
