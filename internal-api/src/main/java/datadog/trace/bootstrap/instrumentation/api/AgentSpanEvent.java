package datadog.trace.bootstrap.instrumentation.api;

import java.util.List;
import java.util.Map;

/**
 * This interface describes an event that occurred during a span lifetime, as the deprecated
 * OpenTelemetry span event concept.
 *
 * @see <a href="https://opentelemetry.io/blog/2026/deprecating-span-events/">Deprecating span
 *     events</a>
 */
public interface AgentSpanEvent {
  /**
   * Gets the event name.
   *
   * @return The event name.
   */
  String name();

  /**
   * Gets the event time.
   *
   * @return The event time, in nanoseconds since the Unix epoch.
   */
  long timeUnixNano();

  /**
   * Gets the event attributes.
   *
   * @return The event attributes, with values being {@link String}, {@link Boolean}, {@link Long},
   *     {@link Double}, or a {@link List} of them.
   */
  Map<String, Object> attributes();
}
