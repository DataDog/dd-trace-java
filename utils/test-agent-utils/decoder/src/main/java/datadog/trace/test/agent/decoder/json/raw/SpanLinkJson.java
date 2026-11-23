package datadog.trace.test.agent.decoder.json.raw;

import com.squareup.moshi.Json;
import datadog.trace.test.agent.decoder.DecodedSpanLink;
import datadog.trace.test.agent.decoder.DecodedSpanLinks;
import java.util.Map;

/**
 * SpanLinkJson decodes a span link from the {@code span_links} field of the JSON trace format the
 * dd-apm-test-agent exposes. The agent fills that field when the tracer submits a {@code v1.0}
 * payload, where links travel as structured span data; on {@code v0.4} / {@code v0.5} they arrive
 * as a meta tag instead, decoded by {@link DecodedSpanLinks}.
 *
 * <p>The two shapes differ: identifiers are decimal here and hexadecimal in the tag, and the trace
 * identifier is split into its low and high halves.
 */
final class SpanLinkJson {
  // Identifiers are unsigned 64-bit, read as decimal strings and parsed with
  // Long.parseUnsignedLong for the same reason as SpanJson: Moshi's long adapter rejects values
  // above Long.MAX_VALUE.
  @Json(name = "trace_id")
  String traceId;

  // The high-order half of a 128-bit trace identifier. Not modeled by DecodedSpanLink, which
  // narrows a trace identifier to its low-order half like the rest of the decoder.
  @Json(name = "trace_id_high")
  String traceIdHigh;

  @Json(name = "span_id")
  String spanId;

  Integer flags;

  String tracestate;

  Map<String, String> attributes;

  DecodedSpanLink toDecodedSpanLink() {
    if (this.traceId == null || this.spanId == null) {
      throw new IllegalStateException(
          "JSON span link missing a required field (trace_id, span_id): " + this);
    }
    try {
      return DecodedSpanLinks.link(
          Long.parseUnsignedLong(this.traceId),
          Long.parseUnsignedLong(this.spanId),
          this.flags == null ? 0 : (byte) this.flags.intValue(),
          this.tracestate,
          this.attributes);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("JSON span link with a malformed identifier: " + this, e);
    }
  }

  @Override
  public String toString() {
    return "SpanLinkJson{"
        + "traceId="
        + this.traceId
        + ", traceIdHigh="
        + this.traceIdHigh
        + ", spanId="
        + this.spanId
        + ", flags="
        + this.flags
        + ", tracestate='"
        + this.tracestate
        + "', attributes="
        + this.attributes
        + '}';
  }
}
