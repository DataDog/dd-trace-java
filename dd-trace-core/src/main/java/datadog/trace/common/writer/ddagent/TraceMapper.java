package datadog.trace.common.writer.ddagent;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.core.DDSpanContext;

public interface TraceMapper extends RemoteMapper {
  static final UTF8BytesString THREAD_NAME = UTF8BytesString.create(DDTags.THREAD_NAME);
  static final UTF8BytesString THREAD_ID = UTF8BytesString.create(DDTags.THREAD_ID);
  static final UTF8BytesString SAMPLING_PRIORITY_KEY =
      UTF8BytesString.create(DDSpanContext.PRIORITY_SAMPLING_KEY);
  static final UTF8BytesString ORIGIN_KEY = UTF8BytesString.create(DDTags.ORIGIN_KEY);
  static final UTF8BytesString PROCESS_TAGS_KEY = UTF8BytesString.create(DDTags.PROCESS_TAGS);

  static final UTF8BytesString SDK_OTLP_EXPORT_KEY = UTF8BytesString.create(DDTags.SDK_OTLP_EXPORT);
  static final UTF8BytesString SDK_OTLP_EXPORT_TRUE = UTF8BytesString.create("true");
  static final UTF8BytesString SDK_OTLP_EXPORT_FALSE = UTF8BytesString.create("false");

  static UTF8BytesString otlpExportMarker(Config config) {
    return config.isOtlpTracesExportEnabled() ? SDK_OTLP_EXPORT_TRUE : SDK_OTLP_EXPORT_FALSE;
  }
}
