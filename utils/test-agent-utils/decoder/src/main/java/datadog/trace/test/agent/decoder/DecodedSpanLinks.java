package datadog.trace.test.agent.decoder;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class decodes the span links the tracer serializes into the {@value #SPAN_LINKS_TAG} meta
 * tag, the only way links reach the agent on the {@code v0.4} / {@code v0.5} / test-agent JSON
 * protocols. On {@code v1.0} links travel as a structured payload field instead, decoded by the
 * {@code v1} span decoder through {@link #link}.
 */
public final class DecodedSpanLinks {
  private static final String SPAN_LINKS_TAG = "_dd.span_links";
  private static final int TRACE_ID_HEX_LENGTH = 16;
  private static final Type LIST_OF_LINKS =
      Types.newParameterizedType(List.class, SpanLinkJson.class);
  private static final JsonAdapter<List<SpanLinkJson>> ADAPTER =
      new Moshi.Builder().build().adapter(LIST_OF_LINKS);

  private DecodedSpanLinks() {}

  /**
   * Decodes the value of meta tags.
   *
   * @param meta The span meta collection.
   * @return The decoded links, in the order the tracer serialized them; empty if the tag is absent
   *     or holds no link.
   * @throws IllegalStateException If the tag value is not a well-formed span-link array.
   */
  public static List<DecodedSpanLink> fromMeta(Map<String, String> meta) {
    String tagValue = meta.get(SPAN_LINKS_TAG);
    if (tagValue == null || tagValue.isEmpty()) {
      return emptyList();
    }
    List<SpanLinkJson> rawLinks;
    try {
      rawLinks = ADAPTER.fromJson(tagValue);
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException("Failed to parse span links: " + tagValue, e);
    }
    if (rawLinks == null || rawLinks.isEmpty()) {
      return emptyList();
    }
    List<DecodedSpanLink> links = new ArrayList<>(rawLinks.size());
    for (SpanLinkJson rawLink : rawLinks) {
      if (rawLink == null) {
        throw new IllegalStateException("Malformed span links with a null link: " + tagValue);
      }
      links.add(toLink(rawLink, tagValue));
    }
    return unmodifiableList(links);
  }

  /**
   * Builds a link from already decoded values, for the structured {@code v1.0} payload field.
   *
   * @param traceId The linked trace identifier.
   * @param spanId The linked span identifier.
   * @param traceFlags The W3C trace flags.
   * @param traceState The W3C trace state, {@code null} for none.
   * @param attributes The link attributes, {@code null} for none.
   * @return The link.
   */
  public static DecodedSpanLink link(
      long traceId,
      long spanId,
      byte traceFlags,
      String traceState,
      Map<String, String> attributes) {
    return new SpanLink(traceId, spanId, traceFlags, traceState, attributes);
  }

  private static DecodedSpanLink toLink(SpanLinkJson rawLink, String tagValue) {
    if (rawLink.traceId == null || rawLink.spanId == null) {
      throw new IllegalStateException(
          "Span link missing a required field (trace_id, span_id): " + tagValue);
    }
    try {
      return new SpanLink(
          Long.parseUnsignedLong(lowOrderTraceId(rawLink.traceId), 16),
          Long.parseUnsignedLong(rawLink.spanId, 16),
          rawLink.flags == null ? 0 : (byte) rawLink.flags.intValue(),
          rawLink.traceState,
          rawLink.attributes);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Span link with a malformed identifier: " + tagValue, e);
    }
  }

  /**
   * Keeps the low-order 64 bits of a serialized trace identifier. The tracer always emits the full
   * 128-bit width, of which the decoder models the low-order half.
   */
  private static String lowOrderTraceId(String traceId) {
    return traceId.length() > TRACE_ID_HEX_LENGTH
        ? traceId.substring(traceId.length() - TRACE_ID_HEX_LENGTH)
        : traceId;
  }

  /** The serialized shape of a single link, as the tracer's {@code DDSpanLink} writes it. */
  private static final class SpanLinkJson {
    @Json(name = "trace_id")
    String traceId;

    @Json(name = "span_id")
    String spanId;

    // Read as an Integer, then narrowed, mirroring how the tracer writes `traceFlags() & 0xFF`.
    Integer flags;

    @Json(name = "tracestate")
    String traceState;

    Map<String, String> attributes;
  }

  private static final class SpanLink implements DecodedSpanLink {
    private final long traceId;
    private final long spanId;
    private final byte traceFlags;
    private final String traceState;
    private final Map<String, String> attributes;

    private SpanLink(
        long traceId,
        long spanId,
        byte traceFlags,
        String traceState,
        Map<String, String> attributes) {
      this.traceId = traceId;
      this.spanId = spanId;
      this.traceFlags = traceFlags;
      this.traceState = traceState == null ? "" : traceState;
      this.attributes =
          attributes == null || attributes.isEmpty()
              ? emptyMap()
              : unmodifiableMap(new HashMap<>(attributes));
    }

    @Override
    public long getTraceId() {
      return this.traceId;
    }

    @Override
    public long getSpanId() {
      return this.spanId;
    }

    @Override
    public byte getTraceFlags() {
      return this.traceFlags;
    }

    @Override
    public String getTraceState() {
      return this.traceState;
    }

    @Override
    public Map<String, String> getAttributes() {
      return this.attributes;
    }

    @Override
    public String toString() {
      // Renders the identifiers the way the wire carries them, so failures read wire-faithfully.
      return "SpanLink{traceId="
          + Long.toHexString(this.traceId)
          + ", spanId="
          + Long.toHexString(this.spanId)
          + ", traceFlags="
          + this.traceFlags
          + ", traceState='"
          + this.traceState
          + "', attributes="
          + this.attributes
          + '}';
    }
  }
}
