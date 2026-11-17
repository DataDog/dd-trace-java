package datadog.trace.test.agent.decoder;

import java.util.List;
import java.util.Map;

/**
 * This interface describes an event a decoded span carries, as the tracer serialized it. It is the
 * wire-side counterpart of the tracer's {@code DDSpanEvent}.
 *
 * <p>Every accessor is non-null: absent attributes decode to an empty map.
 */
public interface DecodedSpanEvent {
  /**
   * Returns the event name.
   *
   * @return The event name.
   */
  String getName();

  /**
   * Returns the event time.
   *
   * @return The event time, in nanoseconds since the Unix epoch.
   */
  long getTimeUnixNano();

  /**
   * Returns the attributes attached to this event.
   *
   * @return The event attributes, with values being {@link String}, {@link Boolean}, {@link Long},
   *     {@link Double}, or a {@link List} of them; empty if the tracer sent none.
   */
  Map<String, Object> getAttributes();
}
