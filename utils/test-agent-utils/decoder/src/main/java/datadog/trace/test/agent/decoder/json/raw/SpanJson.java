package datadog.trace.test.agent.decoder.json.raw;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import com.squareup.moshi.Json;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedSpanLink;
import datadog.trace.test.agent.decoder.DecodedSpanLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SpanJson decodes spans from the JSON trace format the dd-apm-test-agent exposes (e.g. from its
 * {@code /test/traces} endpoint), which serializes spans in the standard v0.4 shape. Field names
 * mirror that wire shape: service/name/resource/type, trace_id/span_id/parent_id,
 * start/duration/error, meta, metrics, and meta_struct.
 */
public final class SpanJson implements DecodedSpan {
  String service;
  String name;
  String resource;
  String type;

  // IDs are unsigned 64-bit; read as decimal strings and parsed with Long.parseUnsignedLong, since
  // Moshi's long adapter rejects values above Long.MAX_VALUE (the agent emits them as JSON numbers,
  // which Moshi coerces to their string form).
  @Json(name = "trace_id")
  String traceId;

  @Json(name = "span_id")
  String spanId;

  @Json(name = "parent_id")
  String parentId;

  // start and duration are required v0.4 fields; boxed so a missing value decodes to null (and is
  // rejected by MessageJson) instead of a silent 0. error is optional per the agent's Span schema
  // (absent => 0, i.e. no error), so it stays a primitive.
  Long start;
  Long duration;
  int error;
  Map<String, String> meta;

  @Json(name = "meta_struct")
  Map<String, Object> metaStruct;

  Map<String, Number> metrics;

  // Ony present in V1 payload. Decoded from meta by #resolveLinks otherwise
  @Json(name = "span_links")
  List<SpanLinkJson> spanLinks;

  // Decoded from span links meta tag or #spanLinks
  // transient so Moshi does not try to decode it
  transient List<DecodedSpanLink> links;

  @Override
  public String getService() {
    return this.service;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public String getResource() {
    return this.resource;
  }

  @Override
  public long getTraceId() {
    return this.traceId == null ? 0L : Long.parseUnsignedLong(this.traceId);
  }

  @Override
  public long getSpanId() {
    return this.spanId == null ? 0L : Long.parseUnsignedLong(this.spanId);
  }

  @Override
  public long getParentId() {
    return this.parentId == null ? 0L : Long.parseUnsignedLong(this.parentId);
  }

  @Override
  public long getStart() {
    return this.start == null ? 0L : this.start;
  }

  @Override
  public long getDuration() {
    return this.duration == null ? 0L : this.duration;
  }

  @Override
  public int getError() {
    return this.error;
  }

  @Override
  public Map<String, String> getMeta() {
    return this.meta == null ? emptyMap() : unmodifiableMap(this.meta);
  }

  @Override
  public Map<String, Object> getMetaStruct() {
    return this.metaStruct == null ? emptyMap() : unmodifiableMap(this.metaStruct);
  }

  @Override
  public Map<String, Number> getMetrics() {
    return this.metrics == null ? emptyMap() : unmodifiableMap(this.metrics);
  }

  @Override
  public String getType() {
    return this.type;
  }

  @Override
  public List<DecodedSpanLink> getLinks() {
    return this.links;
  }

  /**
   * Decodes the span links, from the {@code span_links} field when the agent filled it (the tracer
   * submitted a {@code v1.0} payload) and from the meta tag otherwise.
   */
  void resolveLinks() {
    if (this.spanLinks == null || this.spanLinks.isEmpty()) {
      this.links = DecodedSpanLinks.fromMeta(getMeta());
      return;
    }
    List<DecodedSpanLink> decoded = new ArrayList<>(this.spanLinks.size());
    for (SpanLinkJson spanLink : this.spanLinks) {
      if (spanLink == null) {
        throw new IllegalStateException("Malformed span_links with a null link: " + this);
      }
      decoded.add(spanLink.toDecodedSpanLink());
    }
    this.links = unmodifiableList(decoded);
  }

  @Override
  public String toString() {
    return "SpanJson{"
        + "service='"
        + this.service
        + "', name='"
        + this.name
        + "', resource='"
        + this.resource
        + "', type='"
        + this.type
        + "', traceId="
        + this.traceId
        + ", spanId="
        + this.spanId
        + ", parentId="
        + this.parentId
        + ", start="
        + this.start
        + ", duration="
        + this.duration
        + ", error="
        + this.error
        + ", meta="
        + this.meta
        + ", metaStruct="
        + this.metaStruct
        + ", metrics="
        + this.metrics
        + '}';
  }
}
