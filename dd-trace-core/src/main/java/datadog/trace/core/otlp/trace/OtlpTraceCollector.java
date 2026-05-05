package datadog.trace.core.otlp.trace;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.List;

/** Collects traces ready for export. */
public abstract class OtlpTraceCollector {

  public abstract void addTrace(List<? extends CoreSpan<?>> spans);

  public abstract OtlpPayload collectTraces();
}
