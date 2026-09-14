package datadog.trace.test.agent.decoder;

import java.util.Map;

/**
 * This interface describes a link a decoded span carries to another span, as the tracer serialized
 * it. It is the wire-side counterpart of the tracer's {@code AgentSpanLink}.
 *
 * <p>Every accessor is non-null: the fields the tracer omits when they hold their default value are
 * normalized here rather than reported as {@code null} — absent trace flags decode to {@code 0}, an
 * absent trace state to {@code ""}, and absent attributes to an empty map.
 */
public interface DecodedSpanLink {
  /**
   * Returns the identifier of the trace this link refers to, on the same 64 bits as {@link
   * DecodedSpan#getTraceId()} so the two compare directly.
   *
   * <p>The wire carries 128 bits, but the decoder narrows a trace identifier to its low-order half
   * everywhere — see {@link DecodedSpan#getTraceId()} — so a link is narrowed the same way rather
   * than modelling a width no span can be compared against.
   *
   * @return The identifier of the trace this link refers to.
   */
  long getTraceId();

  /**
   * Returns the identifier of the span this link refers to.
   *
   * @return The unsigned 64-bit span identifier this link refers to.
   */
  long getSpanId();

  /**
   * Returns the W3C trace flags of the linked span.
   *
   * @return The trace flags, {@code 0} if the tracer sent none.
   */
  byte getTraceFlags();

  /**
   * Returns the W3C trace state of the linked span.
   *
   * @return The trace state, empty if the tracer sent none.
   */
  String getTraceState();

  /**
   * Returns the attributes attached to this link.
   *
   * @return The link attributes, empty if the tracer sent none.
   */
  Map<String, String> getAttributes();
}
