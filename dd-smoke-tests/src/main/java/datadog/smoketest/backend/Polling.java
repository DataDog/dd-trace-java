package datadog.smoketest.backend;

import datadog.trace.test.util.PollingConditions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Waits for the messages captured by an {@link AgentBackend}. */
final class Polling {
  private Polling() {}

  /**
   * Waits for a captured message satisfying the given predicate, and returns it.
   *
   * @param messages The captured messages.
   * @param predicate The predicate a message must satisfy.
   * @param timeoutSeconds How long to wait, in seconds.
   * @param description The kind of message, for the failure message.
   * @param <T> The message type.
   * @return The first matching message.
   * @throws AssertionError If no matching message arrives before the timeout.
   */
  static <T> T waitForFirst(
      Supplier<List<T>> messages,
      Predicate<T> predicate,
      double timeoutSeconds,
      String description) {
    AtomicReference<T> match = new AtomicReference<>();
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              for (T message : messages.get()) {
                if (predicate.test(message)) {
                  match.set(message);
                  return;
                }
              }
              throw new AssertionError("No " + description + " matched yet");
            });
    return match.get();
  }
}
