package datadog.smoketest.backend;

import datadog.trace.test.util.PollingConditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Query facade over the app-telemetry messages a {@link TraceBackend} has received (S9). Common to
 * both backends: the mock collects them in-process, the test agent exposes them at {@code
 * /test/session/apmtelemetry}; each message is a decoded JSON map.
 *
 * <p>Telemetry is scoped to the test session (the tracer's telemetry client emits the same {@code
 * X-Datadog-Test-Session-Token} as the trace writer), so it stays isolated per test even on a
 * shared external agent.
 */
public final class Telemetry {
  private static final double DEFAULT_TIMEOUT_SECONDS = 10;

  private final Supplier<List<Map<String, Object>>> source;

  Telemetry(Supplier<List<Map<String, Object>>> source) {
    this.source = source;
  }

  /** The raw telemetry messages received — one map per intake request. */
  public List<Map<String, Object>> getMessages() {
    return this.source.get();
  }

  /**
   * Individual telemetry events, expanding each {@code message-batch} into its {@code payload}
   * entries — the granularity most assertions want (e.g. locating {@code app-started} or {@code
   * app-dependencies-loaded}).
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> getFlatMessages() {
    List<Map<String, Object>> flat = new ArrayList<>();
    for (Map<String, Object> message : getMessages()) {
      Object payload = message.get("payload");
      if ("message-batch".equals(message.get("request_type")) && payload instanceof List) {
        for (Object entry : (List<?>) payload) {
          if (entry instanceof Map) {
            flat.add((Map<String, Object>) entry);
          }
        }
      } else {
        flat.add(message);
      }
    }
    return flat;
  }

  /** Waits (up to the default timeout) until at least {@code count} messages have been received. */
  public Telemetry waitForCount(int count) {
    return waitForCount(count, DEFAULT_TIMEOUT_SECONDS);
  }

  /** As {@link #waitForCount(int)}, but overriding the timeout for this call. */
  public Telemetry waitForCount(int count, double timeoutSeconds) {
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              int actual = getMessages().size();
              if (actual < count) {
                throw new AssertionError(
                    "Expected at least " + count + " telemetry message(s) but got " + actual);
              }
            });
    return this;
  }

  /** Waits (default timeout) until a flattened telemetry event matches {@code predicate}. */
  public Telemetry waitForFlat(Predicate<Map<String, Object>> predicate) {
    return waitForFlat(predicate, DEFAULT_TIMEOUT_SECONDS);
  }

  /** As {@link #waitForFlat(Predicate)}, but overriding the timeout for this call. */
  public Telemetry waitForFlat(Predicate<Map<String, Object>> predicate, double timeoutSeconds) {
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              if (getFlatMessages().stream().noneMatch(predicate)) {
                throw new AssertionError("No telemetry event matched; received: " + getMessages());
              }
            });
    return this;
  }
}
