package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * Randomized invariant testing for {@link StripInjectedContext}, mirroring the repo's existing
 * fuzz-style test pattern (see {@code TagMapFuzzTest}). Generates many random EventBridge-shaped
 * payloads instead of enumerating fixed cases by hand, and asserts core invariants hold for every
 * one of them.
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
