package datadog.trace.common.writer;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.core.otlp.trace.OtlpTraceCollector;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class OtlpPayloadDispatcher implements PayloadDispatcher {
  private final OtlpTraceCollector collector;
  private final OtlpSender sender;
  private final HealthMetrics healthMetrics;

  OtlpPayloadDispatcher(OtlpSender sender, OtlpTraceCollector collector) {
    this(sender, collector, HealthMetrics.NO_OP);
  }

  OtlpPayloadDispatcher(
      OtlpSender sender, OtlpTraceCollector collector, HealthMetrics healthMetrics) {
    this.sender = sender;
    this.collector = collector;
    this.healthMetrics = healthMetrics;
  }

  @Override
  public void addTrace(List<? extends CoreSpan<?>> trace) {
    collector.addTrace(trace);
  }

  @Override
  public void flush() {
    OtlpPayload payload = collector.collectTraces();
    int traceCount = collector.getTraceCount();
    if (payload != OtlpPayload.EMPTY) {
      int sizeInBytes = payload.getContentLength();
      healthMetrics.onSerialize(sizeInBytes);
      RemoteApi.Response response = sender.send(payload);
      if (response.success()) {
        healthMetrics.onSend(traceCount, sizeInBytes, response);
      } else {
        healthMetrics.onFailedSend(traceCount, sizeInBytes, response);
      }
    }
  }

  @Override
  public void onDroppedTrace(int spanCount) {
    // RemoteWriter already updated healthMetrics, no further action required
  }

  @Override
  public Collection<RemoteApi> getApis() {
    return Collections.emptyList();
  }
}
