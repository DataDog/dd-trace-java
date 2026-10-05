package datadog.trace.core.otlp.trace;

import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.List;

/** Collects traces ready for export. */
public abstract class OtlpTraceCollector {

  /** Adds spans from the given trace to the collector. */
  public abstract void addTrace(List<? extends CoreSpan<?>> spans);

  /** Collects all spans added since the last collection. */
  public abstract OtlpPayload collectTraces();

  /** Returns the number of bytes buffered since the last collection. */
  public abstract int sizeInBytes();

  protected final boolean shouldExport(CoreSpan<?> span) {
    return span.samplingPriority() > 0 // trace-level sampling priority
        || span.getTag(SPAN_SAMPLING_MECHANISM_TAG) != null; // span-level sampling priority
  }
}
