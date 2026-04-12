package datadog.trace.core.otlp.trace;

import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.List;

/** Collects trace spans ready for export. */
public interface OtlpTraceCollector {
  OtlpTraceCollector NOOP_COLLECTOR = spans -> OtlpPayload.EMPTY;

  OtlpPayload collectSpans(List<DDSpan> spans);
}
