package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_PARTIAL_VERSION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_TOP_LEVEL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_WAS_LONG_RUNNING;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static datadog.trace.common.writer.RemoteMapper.HTTP_STATUS;
import static datadog.trace.common.writer.ddagent.TraceMapper.ORIGIN_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.PROCESS_TAGS_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.SAMPLING_PRIORITY_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_ID;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_NAME;
import static datadog.trace.core.otlp.common.OtlpCommonJson.hexSpanId;
import static datadog.trace.core.otlp.common.OtlpCommonJson.hexTraceId;
import static datadog.trace.core.otlp.common.OtlpCommonJson.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.NO_TRACE_FLAGS;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.REMOTE_TRACE_FLAG;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.SAMPLED_TRACE_FLAG;
import static datadog.trace.core.otlp.trace.OtlpSpanKind.spanKind;

import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.propagation.PropagationTags;
import java.util.List;
import java.util.Map;

/** Provides writers for OpenTelemetry's "trace.proto" JSON encoding. */
public final class OtlpTraceJson {

  private static final UTF8BytesString SERVICE_NAME = UTF8BytesString.create("service.name");
  private static final UTF8BytesString RESOURCE_NAME = UTF8BytesString.create("resource.name");
  private static final UTF8BytesString OPERATION_NAME = UTF8BytesString.create("operation.name");
  private static final UTF8BytesString SPAN_TYPE = UTF8BytesString.create("span.type");

  private OtlpTraceJson() {}

  /** Writes one complete {@code Span} JSON object. */
  public static void writeSpan(
      JsonWriter writer, DDSpan span, MetaWriter metaWriter, List<? extends AgentSpanLink> links) {
    PropagationTags propagationTags = span.spanContext().getPropagationTags();

    writer.beginObject();

    writer.name("traceId").value(hexTraceId(span.getTraceId()));
    writer.name("spanId").value(hexSpanId(span.getSpanId()));

    String tracestate = propagationTags.getW3CTracestate();
    if (tracestate != null) {
      writer.name("traceState").value(tracestate);
    }

    if (span.getParentId() != 0) {
      writer.name("parentSpanId").value(hexSpanId(span.getParentId()));
    }

    int traceFlags = NO_TRACE_FLAGS;
    if (span.samplingPriority() > 0) {
      traceFlags |= SAMPLED_TRACE_FLAG;
    }
    if (span.spanContext().isRemote()) {
      traceFlags |= REMOTE_TRACE_FLAG;
    }
    if (traceFlags != NO_TRACE_FLAGS) {
      writer.name("flags").value(traceFlags);
    }

    writer.name("name").value(span.getResourceName().toString());
    writer.name("kind").value(spanKind(span.spanContext().getSpanKindString()));
    writer.name("startTimeUnixNano").value(Long.toString(span.getStartTime()));
    writer
        .name("endTimeUnixNano")
        .value(Long.toString(span.getStartTime() + PendingTrace.getDurationNano(span)));

    writer.name("attributes").beginArray();
    if (!Config.get().getServiceName().equals(span.getServiceName())) {
      writeSpanTag(writer, SERVICE_NAME, span.getServiceName());
    }
    writeSpanTag(writer, RESOURCE_NAME, span.getResourceName());
    writeSpanTag(writer, OPERATION_NAME, span.getOperationName());
    if (span.getSpanType() != null) {
      writeSpanTag(writer, SPAN_TYPE, span.getSpanType());
    }
    span.processTagsAndBaggage(metaWriter);
    writer.endArray();

    if (!links.isEmpty()) {
      writer.name("links").beginArray();
      for (AgentSpanLink link : links) {
        writeSpanLink(writer, link);
      }
      writer.endArray();
    }

    if (span.isError()) {
      writer.name("status").beginObject();
      Object errorMessage = span.getTag(DDTags.ERROR_MSG);
      if (errorMessage instanceof String) {
        writer.name("message").value((String) errorMessage);
      }
      writer.name("code").value(2); // STATUS_CODE_ERROR
      writer.endObject();
    }

    writer.endObject();
  }

  /** Writes one complete {@code SpanLink} JSON object. */
  public static void writeSpanLink(JsonWriter writer, AgentSpanLink spanLink) {
    writer.beginObject();

    writer.name("traceId").value(hexTraceId(spanLink.traceId()));
    writer.name("spanId").value(hexSpanId(spanLink.spanId()));
    if (!spanLink.traceState().isEmpty()) {
      writer.name("traceState").value(spanLink.traceState());
    }

    Map<?, ?> attributes = spanLink.attributes().asMap();
    if (!attributes.isEmpty()) {
      writer.name("attributes").beginArray();
      attributes.forEach(
          (key, value) -> writeAttribute(writer, STRING_ATTRIBUTE, key.toString(), value));
      writer.endArray();
    }

    writer.name("flags").value(spanLink.traceFlags() & 0xff);

    writer.endObject();
  }

  private static void writeSpanTag(JsonWriter writer, TagMap.EntryReader tagEntry) {
    switch (tagEntry.type()) {
      case TagMap.EntryReader.BOOLEAN:
        writeAttribute(writer, BOOLEAN_ATTRIBUTE, tagEntry.tag(), tagEntry.objectValue());
        break;
      case TagMap.EntryReader.INT:
      case TagMap.EntryReader.LONG:
        writeAttribute(writer, LONG_ATTRIBUTE, tagEntry.tag(), tagEntry.objectValue());
        break;
      case TagMap.EntryReader.FLOAT:
      case TagMap.EntryReader.DOUBLE:
        writeAttribute(writer, DOUBLE_ATTRIBUTE, tagEntry.tag(), tagEntry.objectValue());
        break;
      default:
        writeAttribute(writer, STRING_ATTRIBUTE, tagEntry.tag(), tagEntry.stringValue());
    }
  }

  private static void writeSpanTag(JsonWriter writer, UTF8BytesString key, CharSequence value) {
    writeAttribute(writer, key, value.toString());
  }

  private static void writeSpanTag(JsonWriter writer, UTF8BytesString key, long value) {
    writeAttribute(writer, key, value);
  }

  public static class MetaWriter implements MetadataConsumer {
    private final JsonWriter writer;

    private boolean includeProcessTags;
    private boolean includeSamplingTags;

    public MetaWriter(JsonWriter writer) {
      this.writer = writer;
    }

    /** Call this to ensure process tags are written out for the next span. */
    public void includeProcessTags() {
      includeProcessTags = true;
    }

    /** Call this to ensure sampling tags are written out for the next span. */
    public void includeSamplingTags() {
      includeSamplingTags = true;
    }

    @Override
    public void accept(Metadata metadata) {
      if ((includeSamplingTags || metadata.topLevel()) && metadata.hasSamplingPriority()) {
        writeSpanTag(writer, SAMPLING_PRIORITY_KEY, metadata.samplingPriority());
      }
      if (metadata.measured()) {
        writeSpanTag(writer, DD_MEASURED, 1);
      }
      if (metadata.topLevel()) {
        writeSpanTag(writer, DD_TOP_LEVEL, 1);
      }

      if (metadata.longRunningVersion() != 0) {
        if (metadata.longRunningVersion() > 0) {
          writeSpanTag(writer, DD_PARTIAL_VERSION, metadata.longRunningVersion());
        } else {
          writeSpanTag(writer, DD_WAS_LONG_RUNNING, 1);
        }
      }

      writeSpanTag(writer, THREAD_ID, metadata.getThreadId());
      writeSpanTag(writer, THREAD_NAME, metadata.getThreadName());
      if (metadata.getHttpStatusCode() != null) {
        writeSpanTag(writer, HTTP_STATUS, metadata.getHttpStatusCode());
      }
      if (metadata.getOrigin() != null) {
        writeSpanTag(writer, ORIGIN_KEY, metadata.getOrigin());
      }
      if (includeProcessTags && metadata.processTags() != null) {
        writeSpanTag(writer, PROCESS_TAGS_KEY, metadata.processTags());
      }

      metadata.getTags().forEach(writer, OtlpTraceJson::writeSpanTag);

      // reset for next span
      includeProcessTags = false;
      includeSamplingTags = false;
    }
  }
}
