package datadog.trace.common.writer;

import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.ExportRecorder;
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
  private final ExportRecorder exportRecorder;

  /**
   * @param exportRecorder reports each send of {@code sender} as a span export
   */
  OtlpPayloadDispatcher(
      OtlpSender sender, OtlpTraceCollector collector, ExportRecorder exportRecorder) {
    this.sender = sender;
    this.collector = collector;
    this.exportRecorder = exportRecorder;
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
    ExportRecorder.Export export = null;
    try {
      OtlpPayload payload = collector.collectTraces();
      if (payload != OtlpPayload.EMPTY) {
        OtlpTelemetry.getInstance().onTracesExportAttempt();
        export = exportRecorder.start(payload.getItemCount());
        RemoteApi.Response response = sender.send(payload);
        export.finish(response);
        OtlpTelemetry.getInstance().onTracesExportComplete(response.success());
      }
    } catch (RuntimeException e) { // don't catch severe Errors
      if (export != null) {
        export.failure(e); // ignored when the export already finished
      }
      log.debug("Failed to send OTLP payload", e);
    } catch (Error e) { // finish the export, but don't swallow severe Errors
      if (export != null) {
        export.failure(e);
      }
      throw e;
    }
  }

  @Override
  public void onDroppedTrace(int spanCount) {
    // no telemetry currently tracked for dropped traces
  }

  @Override
  public Collection<RemoteApi> getApis() {
    return Collections.emptyList();
  }
}
