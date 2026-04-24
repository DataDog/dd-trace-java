package datadog.trace.common.writer;

import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_HTTP_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_HTTP_TRACES_ENDPOINT;

import datadog.communication.ddagent.DroppingPolicy;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.otlp.common.OtlpGrpcSender;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpSender;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OtlpWriter extends RemoteWriter {

  private static final int BUFFER_SIZE = 1024;
  private static final String TRACES_SIGNAL_PATH = "/" + DEFAULT_OTLP_HTTP_TRACES_ENDPOINT;
  private static final String DEFAULT_OTLP_HTTP_ENDPOINT =
      "http://localhost:" + DEFAULT_OTLP_HTTP_PORT + TRACES_SIGNAL_PATH;

  public static OtlpWriterBuilder builder() {
    return new OtlpWriterBuilder();
  }

  private final OtlpSender sender;

  OtlpWriter(
      TraceProcessingWorker worker,
      PayloadDispatcher dispatcher,
      OtlpSender sender,
      HealthMetrics healthMetrics,
      int flushTimeout,
      TimeUnit flushTimeoutUnit,
      boolean alwaysFlush) {
    super(worker, dispatcher, healthMetrics, flushTimeout, flushTimeoutUnit, alwaysFlush);
    this.sender = sender;
  }

  @Override
  public void close() {
    super.close();
    sender.shutdown();
  }

  public static class OtlpWriterBuilder {
    private String endpoint = DEFAULT_OTLP_HTTP_ENDPOINT;
    private Map<String, String> headers = Collections.emptyMap();
    private int timeoutMillis = (int) TimeUnit.SECONDS.toMillis(10);
    private OtlpConfig.Protocol protocol = OtlpConfig.Protocol.HTTP_PROTOBUF;
    private OtlpConfig.Compression compression = OtlpConfig.Compression.NONE;
    private int traceBufferSize = BUFFER_SIZE;
    private HealthMetrics healthMetrics = HealthMetrics.NO_OP;
    private int flushIntervalMilliseconds = 1000;
    private int flushTimeout = 1;
    private TimeUnit flushTimeoutUnit = TimeUnit.SECONDS;
    private boolean alwaysFlush = false;
    private SingleSpanSampler singleSpanSampler;
    private OtlpSender sender;

    public OtlpWriterBuilder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public OtlpWriterBuilder headers(Map<String, String> headers) {
      this.headers = headers;
      return this;
    }

    public OtlpWriterBuilder timeoutMillis(int timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }

    public OtlpWriterBuilder protocol(OtlpConfig.Protocol protocol) {
      this.protocol = protocol;
      return this;
    }

    public OtlpWriterBuilder compression(OtlpConfig.Compression compression) {
      this.compression = compression;
      return this;
    }

    public OtlpWriterBuilder traceBufferSize(int traceBufferSize) {
      this.traceBufferSize = traceBufferSize;
      return this;
    }

    public OtlpWriterBuilder healthMetrics(HealthMetrics healthMetrics) {
      this.healthMetrics = healthMetrics;
      return this;
    }

    public OtlpWriterBuilder flushIntervalMilliseconds(int flushIntervalMilliseconds) {
      this.flushIntervalMilliseconds = flushIntervalMilliseconds;
      return this;
    }

    public OtlpWriterBuilder flushTimeout(int flushTimeout, TimeUnit flushTimeoutUnit) {
      this.flushTimeout = flushTimeout;
      this.flushTimeoutUnit = flushTimeoutUnit;
      return this;
    }

    public OtlpWriterBuilder alwaysFlush(boolean alwaysFlush) {
      this.alwaysFlush = alwaysFlush;
      return this;
    }

    public OtlpWriterBuilder spanSamplingRules(SingleSpanSampler singleSpanSampler) {
      this.singleSpanSampler = singleSpanSampler;
      return this;
    }

    OtlpWriterBuilder sender(OtlpSender sender) {
      this.sender = sender;
      return this;
    }

    public OtlpWriter build() {
      if (sender == null) {
        sender =
            protocol == OtlpConfig.Protocol.GRPC
                ? new OtlpGrpcSender(
                    endpoint, TRACES_SIGNAL_PATH, headers, timeoutMillis, compression)
                : new OtlpHttpSender(
                    endpoint, TRACES_SIGNAL_PATH, headers, timeoutMillis, compression);
      }

      final OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender);
      final TraceProcessingWorker worker =
          new TraceProcessingWorker(
              traceBufferSize,
              healthMetrics,
              dispatcher,
              DroppingPolicy.DISABLED,
              Prioritization.ENSURE_TRACE,
              flushIntervalMilliseconds,
              TimeUnit.MILLISECONDS,
              singleSpanSampler);

      return new OtlpWriter(
          worker, dispatcher, sender, healthMetrics, flushTimeout, flushTimeoutUnit, alwaysFlush);
    }
  }
}
