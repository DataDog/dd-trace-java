package datadog.trace.core;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import com.squareup.moshi.Types;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** This class holds helper methods to encode span links into span context. */
public class DDSpanLink extends SpanLink {
  private static final Moshi MOSHI = new Moshi.Builder().add(new SpanLinkAdapter()).build();
  private static final JsonAdapter<List<AgentSpanLink>> SPAN_LINKS_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(List.class, AgentSpanLink.class));

  protected DDSpanLink(
      DDTraceId traceId, long spanId, String traceState, Map<String, String> attributes) {
    super(traceId, spanId, traceState, attributes);
  }

  /**
   * Creates a span link from an {@link ExtractedContext}. Gathers the trace and span identifiers,
   * and the W3C trace state from the given instance.
   *
   * @param context The context of the span to get the link to.
   * @return A span link to the given context.
   */
  public static SpanLink from(ExtractedContext context) {
    return from(context, Collections.emptyMap());
  }

  /**
   * Creates a span link from an {@link ExtractedContext} with custom attributes. Gathers the trace
   * and span identifiers, and the W3C trace state from the given instance.
   *
   * @param context The context of the span to get the link to.
   * @param attributes The span link attributes.
   * @return A span link to the given context with custom attributes.
   */
  public static SpanLink from(ExtractedContext context, Map<String, String> attributes) {
    String traceState =
        context.getPropagationTags() == null
            ? ""
            : context.getPropagationTags().headerValue(PropagationTags.HeaderType.W3C);
    return new DDSpanLink(context.getTraceId(), context.getSpanId(), traceState, attributes);
  }

  /**
   * Encode a span link collection into a tag value.
   *
   * @param links The span link collection to encode.
   * @return The encoded tag value, {@code null} if no links.
   */
  public static String toTag(List<AgentSpanLink> links) {
    if (links == null || links.isEmpty()) {
      return null;
    }
    return SPAN_LINKS_ADAPTER.toJson(links);
  }

  private static class SpanLinkAdapter {
    @ToJson
    SpanLinkJson toSpanLinkJson(AgentSpanLink link) {
      SpanLinkJson json = new SpanLinkJson();
      json.traceId = link.traceId().toHexString();
      json.spanId = DDSpanId.toHexString(link.spanId());
      json.traceState = link.traceState();
      if (!link.attributes().isEmpty()) {
        json.attributes = link.attributes();
      }
      return json;
    }

    @FromJson
    AgentSpanLink fromSpanLinkJson(SpanLinkJson json) {
      return new DDSpanLink(
          DDTraceId.fromHex(json.traceId),
          DDSpanId.fromHex(json.spanId),
          json.traceState,
          json.attributes);
    }
  }

  private static class SpanLinkJson {
    String traceId;
    String spanId;
    String traceState;
    Map<String, String> attributes;
  }
}
