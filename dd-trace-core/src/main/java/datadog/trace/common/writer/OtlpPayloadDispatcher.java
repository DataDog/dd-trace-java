package datadog.trace.common.writer;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.core.otlp.trace.OtlpTraceCollector;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OtlpPayloadDispatcher implements PayloadDispatcher {
  private static final Logger log = LoggerFactory.getLogger(OtlpPayloadDispatcher.class);

  private static final int FLUSH_THRESHOLD_BYTES = 5 << 20; // 5 MiB

  private final OtlpTraceCollector collector;
  private final OtlpSender sender;

  OtlpPayloadDispatcher(OtlpSender sender, OtlpTraceCollector collector) {
    this.sender = sender;
    this.collector = collector;
  }

  @Override
  public void addTrace(List<? extends CoreSpan<?>> trace) {
    collector.addTrace(trace);
    // flush proactively to keep payload size bounded
    if (collector.sizeInBytes() >= FLUSH_THRESHOLD_BYTES) {
      flush();
    }
  }

  @Override
  public void flush() {
    try {
      OtlpPayload payload = collector.collectTraces();
      if (payload != OtlpPayload.EMPTY) {
        sender.send(payload);
      }
    } catch (RuntimeException e) { // don't catch severe Errors
      log.debug("Failed to send OTLP payload", e);
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
