package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_PARTIAL_VERSION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_TOP_LEVEL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_WAS_LONG_RUNNING;
import static datadog.trace.common.writer.ddagent.TraceMapper.ORIGIN_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.PROCESS_TAGS_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.SAMPLING_PRIORITY_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_ID;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_NAME;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Span attribute keys shared by the OTLP protobuf and JSON trace writers. */
final class OtlpTraceAttributes {

  static final UTF8BytesString RESOURCE_NAME = UTF8BytesString.create("resource.name");
  static final UTF8BytesString OPERATION_NAME = UTF8BytesString.create("operation.name");
  static final UTF8BytesString SPAN_TYPE = UTF8BytesString.create("span.type");

  /*
   * Span fields and intercepted Metadata fields bypass TagMap entry projection. Resolve their
   * keys through the registry here so OpenTelemetry names stay consistent with entry tags.
   */
  static final UTF8BytesString SERVICE_NAME_KEY = otelKey(KnownTags.SERVICE_ID);

  static final UTF8BytesString HTTP_STATUS_CODE_KEY = otelKey(KnownTags.HTTP_STATUS_CODE_ID);

  static final UTF8BytesString HTTP_STATUS_CODE_KEY_DD =
      UTF8BytesString.create(KnownTags.HTTP_STATUS_CODE_NAME);

  /**
   * Keys that baggage must not export as attributes: the attributes the writers emit directly from
   * span fields and intercepted Metadata fields (the span's own value wins), plus _dd.p.tid, whose
   * upper 64 bits the OTLP traceId already carries.
   */
  static final Set<String> SKIPPED_BAGGAGE_KEYS =
      new HashSet<>(
          Arrays.asList(
              "_dd.p.tid",
              SERVICE_NAME_KEY.toString(),
              RESOURCE_NAME.toString(),
              OPERATION_NAME.toString(),
              SPAN_TYPE.toString(),
              SAMPLING_PRIORITY_KEY.toString(),
              DD_MEASURED.toString(),
              DD_TOP_LEVEL.toString(),
              DD_PARTIAL_VERSION.toString(),
              DD_WAS_LONG_RUNNING.toString(),
              THREAD_ID.toString(),
              THREAD_NAME.toString(),
              HTTP_STATUS_CODE_KEY.toString(),
              HTTP_STATUS_CODE_KEY_DD.toString(),
              ORIGIN_KEY.toString(),
              PROCESS_TAGS_KEY.toString()));

  /** The OpenTelemetry-namespace key for a known tag, as named by the registry. */
  private static UTF8BytesString otelKey(long tagId) {
    return UTF8BytesString.create(KnownTagCodec.openTelemetryTagOf(tagId));
  }

  private OtlpTraceAttributes() {}
}
