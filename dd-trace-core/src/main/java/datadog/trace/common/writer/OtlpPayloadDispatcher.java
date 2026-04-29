package datadog.trace.common.writer;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.core.otlp.trace.OtlpTraceCollector;
import datadog.trace.core.otlp.trace.OtlpTraceProtoCollector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class OtlpPayloadDispatcher implements PayloadDispatcher {
  private final OtlpTraceCollector collector;
  private final OtlpSender sender;

  OtlpPayloadDispatcher(OtlpSender sender) {
    this(sender, OtlpTraceProtoCollector.INSTANCE);
  }

  OtlpPayloadDispatcher(OtlpSender sender, OtlpTraceCollector collector) {
    this.sender = sender;
    this.collector = collector;
  }

  @Override
  public void addTrace(List<? extends CoreSpan<?>> trace) {
    List<CoreSpan<?>> sampled = null;
    for (CoreSpan<?> span : trace) {
      if (shouldExport(span)) {
        if (sampled == null) {
          sampled = new ArrayList<>(trace.size());
        }
        sampled.add(span);
      }
    }
    if (sampled != null) {
      collector.addTrace(sampled);
    }
  }

  @Override
  public void flush() {
    OtlpPayload payload = collector.collectTraces();
    if (payload != OtlpPayload.EMPTY) {
      sender.send(payload);
    }
  }

  @Override
  public void onDroppedTrace(int spanCount) {
    // TODO: surface drop counts via HealthMetrics
  }

  @Override
  public Collection<RemoteApi> getApis() {
    return Collections.emptyList();
  }

  private static boolean shouldExport(CoreSpan<?> span) {
    // trace-level sampling priority
    if (span.samplingPriority() > 0) {
      return true;
    }
    // span-level sampling priority
    return span.getTag(DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG) != null;
  }
}
