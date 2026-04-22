package datadog.trace.common.writer;

import static datadog.communication.http.OkHttpUtils.buildHttpClient;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class DDAgentWriter extends RemoteWriter {

  public static DDAgentWriterBuilder builder() {
    return new DDAgentWriterBuilder();
  }

  private static final int BUFFER_SIZE = 1024;

  public static class DDAgentWriterBuilder {

    String agentHost = DEFAULT_AGENT_HOST;
    int traceAgentPort = DEFAULT_TRACE_AGENT_PORT;
    String unixDomainSocket = null;
    String namedPipe = null;
    long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_AGENT_TIMEOUT);
    int traceBufferSize = BUFFER_SIZE;
    HealthMetrics healthMetrics = HealthMetrics.NO_OP;
    int flushIntervalMilliseconds = 1000;
    Monitoring monitoring = Monitoring.DISABLED;
    boolean traceAgentV05Enabled = Config.get().isTraceAgentV05Enabled();
    boolean metricsReportingEnabled = Config.get().isTracerMetricsEnabled();
    private int flushTimeout = 1;
    private TimeUnit flushTimeoutUnit = TimeUnit.SECONDS;
    boolean alwaysFlush = false;

    private DDAgentApi agentApi;
    private Prioritization prioritization;
    private DDAgentFeaturesDiscovery featureDiscovery;
    private SingleSpanSampler singleSpanSampler;

    public DDAgentWriterBuilder agentApi(DDAgentApi agentApi) {
      this.agentApi = agentApi;
      return this;
    }

    public DDAgentWriterBuilder agentHost(String agentHost) {
      this.agentHost = agentHost;
      return this;
    }

    public DDAgentWriterBuilder traceAgentPort(int traceAgentPort) {
      this.traceAgentPort = traceAgentPort;
      return this;
    }

    public DDAgentWriterBuilder unixDomainSocket(String unixDomainSocket) {
      this.unixDomainSocket = unixDomainSocket;
      return this;
    }

    public DDAgentWriterBuilder namedPipe(String namedPipe) {
      this.namedPipe = namedPipe;
      return this;
    }

    public DDAgentWriterBuilder timeoutMillis(long timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }

    public DDAgentWriterBuilder traceBufferSize(int traceBufferSize) {
      this.traceBufferSize = traceBufferSize;
      return this;
    }

    public DDAgentWriterBuilder healthMetrics(HealthMetrics healthMetrics) {
      this.healthMetrics = healthMetrics;
      return this;
    }

    public DDAgentWriterBuilder flushIntervalMilliseconds(int flushIntervalMilliseconds) {
      this.flushIntervalMilliseconds = flushIntervalMilliseconds;
      return this;
    }

    public DDAgentWriterBuilder prioritization(Prioritization prioritization) {
      this.prioritization = prioritization;
      return this;
    }

    public DDAgentWriterBuilder monitoring(Monitoring monitoring) {
      this.monitoring = monitoring;
      return this;
    }

    public DDAgentWriterBuilder traceAgentV05Enabled(boolean traceAgentV05Enabled) {
      this.traceAgentV05Enabled = traceAgentV05Enabled;
      return this;
    }

    public DDAgentWriterBuilder metricsReportingEnabled(boolean metricsReportingEnabled) {
      this.metricsReportingEnabled = metricsReportingEnabled;
      return this;
    }

    public DDAgentWriterBuilder featureDiscovery(DDAgentFeaturesDiscovery featureDiscovery) {
      this.featureDiscovery = featureDiscovery;
      return this;
    }

    public DDAgentWriterBuilder flushTimeout(int flushTimeout, TimeUnit flushTimeoutUnit) {
      this.flushTimeout = flushTimeout;
      this.flushTimeoutUnit = flushTimeoutUnit;
      return this;
    }

    public DDAgentWriterBuilder alwaysFlush(boolean alwaysFlush) {
      this.alwaysFlush = alwaysFlush;
      return this;
    }

    public DDAgentWriterBuilder spanSamplingRules(SingleSpanSampler singleSpanSampler) {
      this.singleSpanSampler = singleSpanSampler;
      return this;
    }

    public DDAgentWriter build() {
      final HttpUrl agentUrl = HttpUrl.get("http://" + agentHost + ":" + traceAgentPort);
      final OkHttpClient client =
          null == featureDiscovery || null == agentApi
              ? buildHttpClient(true, unixDomainSocket, namedPipe, timeoutMillis)
              : null;
      if (null == featureDiscovery) {
        featureDiscovery =
            new DDAgentFeaturesDiscovery(
                client, monitoring, agentUrl, traceAgentV05Enabled, metricsReportingEnabled);
      }
      if (null == agentApi) {
        agentApi =
            new DDAgentApi(client, agentUrl, featureDiscovery, monitoring, metricsReportingEnabled);
      }

      final DDAgentMapperDiscovery mapperDiscovery = new DDAgentMapperDiscovery(featureDiscovery);
      final PayloadDispatcher dispatcher =
          new PayloadDispatcherImpl(mapperDiscovery, agentApi, healthMetrics, monitoring);
      final TraceProcessingWorker traceProcessingWorker =
          new TraceProcessingWorker(
              traceBufferSize,
              healthMetrics,
              dispatcher,
              featureDiscovery,
              null == prioritization ? FAST_LANE : prioritization,
              flushIntervalMilliseconds,
              TimeUnit.MILLISECONDS,
              singleSpanSampler);

      return new DDAgentWriter(
          traceProcessingWorker,
          dispatcher,
          healthMetrics,
          flushTimeout,
          flushTimeoutUnit,
          alwaysFlush);
    }
  }

  DDAgentWriter(
      TraceProcessingWorker worker,
      PayloadDispatcher dispatcher,
      HealthMetrics healthMetrics,
      int flushTimeout,
      TimeUnit flushTimeoutUnit,
      boolean alwaysFlush) {
    super(worker, dispatcher, healthMetrics, flushTimeout, flushTimeoutUnit, alwaysFlush);
  }
}
