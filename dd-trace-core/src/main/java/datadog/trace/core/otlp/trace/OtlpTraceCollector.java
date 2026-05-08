package datadog.trace.core.otlp.trace;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.List;

/** Collects traces ready for export. */
public abstract class OtlpTraceCollector {

  /** Adds spans from the given trace to the collector. */
  public abstract void addTrace(List<? extends CoreSpan<?>> spans);

  /** Collects all spans added since the last collection. */
  public abstract OtlpPayload collectTraces();
}
