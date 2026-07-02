package datadog.trace.bootstrap.instrumentation.api;

import java.util.Map;

/**
 * Describes an event that occurred during a span's lifetime (the OpenTelemetry span-event concept).
 *
 * <p>Span events are kept as structured data until serialization so the different trace payloads
 * can encode them in their own representation without a JSON round-trip:
 *
 * <ul>
 *   <li>the V1 payload encodes them natively (span field {@code 12}) from {@link #timeNanos()},
 *       {@link #name()} and {@link #attributes()};
 *   <li>the legacy v0.x payloads carry them as the JSON string {@code events} tag, assembled from
 *       each event's {@link #toJson()}.
 * </ul>
 *
 * @see AgentSpanLink for the analogous structured representation of span links.
 */
public interface AgentSpanEvent {
  /**
   * Gets the event timestamp.
   *
   * @return The event timestamp, in nanoseconds since the Unix epoch.
   */
  long timeNanos();

  /**
   * Gets the event name.
   *
   * @return The event name.
   */
  String name();

  /**
   * Gets the event attributes as typed values. Values are {@link String}, {@link Boolean}, {@link
   * Long}, {@link Double}, or a {@link java.util.List} of those, mirroring the OpenTelemetry
   * attribute types.
   *
   * @return The event attributes.
   */
  Map<String, Object> attributes();

  /**
   * Serializes the event as a JSON string.
   *
   * @return The event as a JSON string.
   */
  CharSequence toJson();
}
