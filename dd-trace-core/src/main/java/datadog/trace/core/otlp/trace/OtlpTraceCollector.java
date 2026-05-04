package datadog.trace.core.otlp.trace;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.List;

/** Collects traces ready for export. */
public interface OtlpTraceCollector {
  OtlpTraceCollector NOOP_COLLECTOR = () -> OtlpPayload.EMPTY;

  OtlpPayload collectTraces();

  default void addTrace(List<? extends CoreSpan<?>> spans) {}
}
