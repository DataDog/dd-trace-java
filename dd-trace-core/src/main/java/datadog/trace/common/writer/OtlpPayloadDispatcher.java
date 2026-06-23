package datadog.trace.common.writer;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.core.otlp.trace.OtlpTraceCollector;
import datadog.trace.core.otlp.trace.OtlpTraceProtoCollector;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class OtlpPayloadDispatcher implements PayloadDispatcher {
  private final OtlpTraceCollector collector;
  private final OtlpSender sender;

  OtlpPayloadDispatcher(OtlpSender sender) {
    this(sender, new OtlpTraceProtoCollector());
  }

  OtlpPayloadDispatcher(OtlpSender sender, OtlpTraceCollector collector) {
    this.sender = sender;
    this.collector = collector;
  }

  @Override
  public void addTrace(List<? extends CoreSpan<?>> trace) {
    collector.addTrace(trace);
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
}
