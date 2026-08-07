package datadog.smoketest.backend;

import datadog.trace.test.util.PollingConditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * This class is query facade over the app-telemetry messages a {@link AgentBackend} has received.
 */
public final class Telemetry {
  private static final double DEFAULT_TIMEOUT_SECONDS = 30;

  private final Supplier<List<Map<String, Object>>> source;

  Telemetry(Supplier<List<Map<String, Object>>> source) {
    this.source = source;
  }

  /**
   * Returns the raw telemetry messages received, one map per intake request.
   *
   * @return The raw telemetry messages.
   */
  public List<Map<String, Object>> getMessages() {
    return this.source.get();
  }

  /**
   * Returns individual telemetry events, expanding each {@code message-batch} into its {@code
   * payload} entries.
   *
   * @return The flattened telemetry events.
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

  /**
   * Waits (up to the default timeout) until at least {@code count} messages have been received.
   *
   * @param count The minimum number of messages to wait for.
   */
  public void waitForCount(int count) {
    waitForCount(count, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * As {@link #waitForCount(int)}, but overriding the timeout for this call.
   *
   * @param count The minimum number of messages to wait for.
   * @param timeoutSeconds How long to wait, in seconds.
   */
  public void waitForCount(int count, double timeoutSeconds) {
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              int actual = getMessages().size();
              if (actual < count) {
                throw new AssertionError(
                    "Expected at least " + count + " telemetry message(s) but got " + actual);
              }
            });
  }

  /**
   * Waits (default timeout) until a flattened telemetry event matches the given predicate.
   *
   * @param predicate The predicate a flattened telemetry event must satisfy.
   */
  public void waitForFlat(Predicate<Map<String, Object>> predicate) {
    waitForFlat(predicate, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * As {@link #waitForFlat(Predicate)}, but overriding the timeout for this call.
   *
   * @param predicate The predicate a flattened telemetry event must satisfy.
   * @param timeoutSeconds How long to wait, in seconds.
   */
  public void waitForFlat(Predicate<Map<String, Object>> predicate, double timeoutSeconds) {
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              if (getFlatMessages().stream().noneMatch(predicate)) {
                throw new AssertionError("No telemetry event matched; received: " + getMessages());
              }
            });
  }
}
