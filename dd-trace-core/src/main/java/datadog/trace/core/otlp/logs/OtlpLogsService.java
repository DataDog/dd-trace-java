package datadog.trace.core.otlp.logs;

import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_LOGS_EXPORTER;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.trace.api.Config;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.otlp.common.OtlpGrpcSender;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodic service to collect OpenTelemetry logs and export them over OTLP. */
public final class OtlpLogsService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpLogsService.class);

  public static final OtlpLogsService INSTANCE = new OtlpLogsService(Config.get());

  private final int intervalMillis;
  private final OtlpLogsCollector collector;
  private final OtlpSender sender;

  private volatile Thread exporterThread;

  OtlpLogsService(Config config) {
    intervalMillis = config.getLogsOtelInterval();
    switch (config.getOtlpLogsProtocol()) {
      case GRPC:
        this.collector = OtlpLogsProtoCollector.INSTANCE;
        this.sender =
            new OtlpGrpcSender(
                config.getOtlpLogsEndpoint(),
                "/opentelemetry.proto.collector.logs.v1.LogsService/Export",
                config.getOtlpLogsHeaders(),
                config.getOtlpLogsTimeout(),
                config.getOtlpLogsCompression());
        break;
      case HTTP_PROTOBUF:
        this.collector = OtlpLogsProtoCollector.INSTANCE;
        this.sender =
            new OtlpHttpSender(
                config.getOtlpLogsEndpoint(),
                "/v1/logs",
                config.getOtlpLogsHeaders(),
                config.getOtlpLogsTimeout(),
                config.getOtlpLogsCompression());
        break;
      case HTTP_JSON:
        this.collector = OtlpLogsJsonCollector.INSTANCE;
        this.sender =
            new OtlpHttpSender(
                config.getOtlpLogsEndpoint(),
                "/v1/logs",
                config.getOtlpLogsHeaders(),
                config.getOtlpLogsTimeout(),
                config.getOtlpLogsCompression());
        break;
      default:
        LOGGER.debug("Unsupported OTLP logs protocol: {}", config.getOtlpLogsProtocol());
        this.collector = null;
        this.sender = null;
    }
  }

  OtlpSender getSender() {
    return sender;
  }

  OtlpLogsCollector getCollector() {
    return collector;
  }

  public void start() {
    if (sender == null) {
      return;
    }

    exporterThread = newAgentThread(OTLP_LOGS_EXPORTER, this::export);
    exporterThread.start();
  }

  public void flush() {
    Thread thread = exporterThread;
    if (thread != null) {
      thread.interrupt();
    }
  }

  public void shutdown() {
    Thread thread = exporterThread;
    if (thread != null) {
      exporterThread = null;
      thread.interrupt();
      try {
        thread.join(1_000);
      } catch (InterruptedException ignore) {
        // don't set interrupt flag as we're mid-shutdown
      }
    }
    if (sender != null) {
      sender.shutdown();
    }
  }

  private void export() {
    while (Thread.currentThread() == exporterThread) {
      try {
        OtlpPayload payload = collector.waitForLogs(intervalMillis);
        if (payload != OtlpPayload.EMPTY) {
          int logRecordCount = collector.getLogRecordCount();
          RemoteApi.Response response = sender.send(payload);
          if (response.success()) {
            OtlpTelemetry.getInstance().onLogRecordsSubmitted(logRecordCount);
          }
        }
      } catch (RuntimeException e) {
        LOGGER.debug("Uncaught exception exporting logs", e);
      }
    }
  }
}
