package datadog.trace.instrumentation.aws.v1.lambda;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * Randomized invariant testing for {@link StripInjectedContext}, mirroring the repo's existing
 * fuzz-style test pattern (see {@code TagMapFuzzTest}). Generates many random payloads in
 * EventBridge, SQS, and SNS shapes instead of enumerating fixed cases by hand, and asserts core
 * invariants hold for every one of them.
 */
public final class StripInjectedContextFuzzTest {

  private static final int ITERATIONS = 5_000;

  @Test
  void neverThrowsAndAlwaysRemovesDatadogWhenPresent() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    for (int i = 0; i < ITERATIONS; i++) {
      byte[] payload = randomPayload(random);

      byte[] result;
      try {
        result = StripInjectedContext.stripInternal(payload);
      } catch (Throwable t) {
        fail(
            "stripInternal must never throw, but threw for payload: "
                + new String(payload, StandardCharsets.UTF_8),
            t);
        return;
      }

      String resultJson = new String(result, StandardCharsets.UTF_8);
      assertFalse(
          resultJson.contains("_datadog"), "output must never contain _datadog: " + resultJson);

      // Idempotency: stripping an already-stripped payload must be a no-op.
      byte[] second = StripInjectedContext.stripInternal(result);
      assertTrue(Arrays.equals(result, second), "stripInternal must be idempotent");
    }
  }

  private static byte[] randomPayload(ThreadLocalRandom random) {
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
  private static byte[] randomEventBridgePayload(ThreadLocalRandom random) {
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
  private static byte[] randomTopLevelCarrierPayload(ThreadLocalRandom random) {
    boolean includeDatadog = random.nextBoolean();
    // Allow 0 regular fields so the sole-field case ({"_datadog":{...}}) is also generated.
    int fieldCount = random.nextInt(0, 4);
    // Choose a random insertion position for the _datadog key among the regular fields.
    // Position 0 = first, fieldCount = last, values in between = middle.
    int datadogPos = includeDatadog ? random.nextInt(0, fieldCount + 1) : fieldCount + 1;

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

  private static String randomDetailObject(ThreadLocalRandom random, boolean includeDatadog) {
    StringBuilder sb = new StringBuilder("{");
    int fieldCount = random.nextInt(1, 6);
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
  private static byte[] randomStringEncodedFieldPayload(ThreadLocalRandom random) {
    boolean includeDatadog = random.nextBoolean();
    String innerJson = randomDetailObject(random, includeDatadog);
    String escaped = innerJson.replace("\"", "\\\"");
    String payload =
        "{\"Type\":\"Notification\",\"Message\":\"" + escaped + "\",\"Subject\":\"test\"}";
    return payload.getBytes(StandardCharsets.UTF_8);
  }

  private static void appendRandomValue(StringBuilder sb, ThreadLocalRandom random) {
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
}
