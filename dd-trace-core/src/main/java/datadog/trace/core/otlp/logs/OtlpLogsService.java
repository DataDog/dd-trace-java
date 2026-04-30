package datadog.trace.core.otlp.logs;

import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_LOGS_EXPORTER;

import datadog.trace.api.Config;
import datadog.trace.core.otlp.common.OtlpGrpcSender;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodic service to collect OpenTelemetry logs and export them over OTLP. */
public final class OtlpLogsService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpLogsService.class);

  public static final OtlpLogsService INSTANCE = new OtlpLogsService(Config.get());

  private final AgentTaskScheduler scheduler;
  private final OtlpLogsCollector collector;
  private final OtlpSender sender;

  private final int intervalMillis;

  private OtlpLogsService(Config config) {
    this.scheduler = new AgentTaskScheduler(OTLP_LOGS_EXPORTER);

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
      default:
        LOGGER.debug("Unsupported OTLP logs protocol: {}", config.getOtlpLogsProtocol());
        this.collector = OtlpLogsCollector.NOOP_COLLECTOR;
        this.sender = null;
    }

    this.intervalMillis = config.getLogsOtelInterval();
  }

  public void start() {
    // add random jitter of up to 5 seconds to initial delay; avoids a fleet
    // of apps starting at the same time from exporting OTLP logs in sync
    long initialMillis =
        intervalMillis
            + Math.min(
                (long)
                    (500d
                        * Math.log(ThreadLocalRandom.current().nextDouble())
                        / Math.log(1 - 0.25)),
                5_000);

    scheduler.scheduleAtFixedRate(
        this::export, initialMillis, intervalMillis, TimeUnit.MILLISECONDS);
  }

  public void flush() {
    scheduler.execute(this::export);
  }

  public void shutdown() {
    if (sender != null) {
      sender.shutdown();
    }
  }

  private void export() {
    OtlpPayload payload = collector.collectLogs();
    if (payload != OtlpPayload.EMPTY) {
      sender.send(payload);
    }
  }
}
