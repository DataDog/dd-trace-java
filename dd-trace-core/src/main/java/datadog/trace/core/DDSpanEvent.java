package datadog.trace.core;

import java.util.List;
import java.util.Map;

/**
 * This class describes an event that occurred during a span lifetime, as the deprecated
 * OpenTelemetry span event concept.
 *
 * @see <a href="https://opentelemetry.io/blog/2026/deprecating-span-events/">Deprecating span
 *     events</a>
 */
public final class DDSpanEvent {
  private final String name;
  private final long timeUnixNano;
  private final Map<String, ?> attributes;

  /**
   * Creates a span event.
   *
   * @param name The event name.
   * @param timeUnixNano The event time, in nanoseconds since the Unix epoch.
   * @param attributes The event attributes, with values being {@link String}, {@link Boolean},
   *     {@link Long}, {@link Double}, or a {@link List} of them.
   */
  public DDSpanEvent(String name, long timeUnixNano, Map<String, ?> attributes) {
    this.name = name;
    this.timeUnixNano = timeUnixNano;
    this.attributes = attributes;
  }

  /**
   * Gets the event name.
   *
   * @return The event name.
   */
  public String name() {
    return this.name;
  }

  /**
   * Gets the event time.
   *
   * @return The event time, in nanoseconds since the Unix epoch.
   */
  public long timeUnixNano() {
    return this.timeUnixNano;
  }

  /**
   * Gets the event attributes.
   *
   * @return The event attributes, with values being {@link String}, {@link Boolean}, {@link Long},
   *     {@link Double}, or a {@link List} of them.
   */
  public Map<String, ?> attributes() {
    return this.attributes;
  }

  @Override
  public String toString() {
    return "DDSpanEvent{name='"
        + this.name
        + "', timeUnixNano="
        + this.timeUnixNano
        + ", attributes="
        + this.attributes
        + '}';
  }
}
