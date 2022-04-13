package datadog.trace.common.writer;

import static datadog.communication.http.OkHttpUtils.buildHttpClient;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.common.writer.ddagent.PayloadDispatcher;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddagent.TraceProcessingWorker;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This writer buffers traces and sends them to the provided DDApi instance. Buffering is done with
 * a distruptor to limit blocking the application threads. Internally, the trace is serialized and
 * put onto a separate disruptor that does block to decouple the CPU intensive from the IO bound
 * threads.
 *
 * <p>[Application] -> [trace processing buffer] -> [serialized trace batching buffer] -> [dd-agent]
 *
 * <p>Note: the first buffer is non-blocking and will discard if full, the second is blocking and
 * will cause back pressure on the trace processing (serializing) thread.
 *
 * <p>If the buffer is filled traces are discarded before serializing. Once serialized every effort
 * is made to keep, to avoid wasting the serialization effort.
 */
public class DDAgentWriter implements Writer {

  private static final Logger log = LoggerFactory.getLogger(DDAgentWriter.class);

  public static DDAgentWriterBuilder builder() {
    return new DDAgentWriterBuilder();
  }

  private static final int BUFFER_SIZE = 1024;

  private final DDAgentApi api;
  private final TraceProcessingWorker traceProcessingWorker;
  private final PayloadDispatcher dispatcher;
  private final DDAgentFeaturesDiscovery discovery;
  private final boolean alwaysFlush;
  private final boolean skipRoot;

  private volatile boolean closed;

  public final HealthMetrics healthMetrics;

  public static class DDAgentWriterBuilder {
    String agentHost = DEFAULT_AGENT_HOST;
    int traceAgentPort = DEFAULT_TRACE_AGENT_PORT;
    String unixDomainSocket = null;
    String namedPipe = null;
    long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_AGENT_TIMEOUT);
    int traceBufferSize = BUFFER_SIZE;
    HealthMetrics healthMetrics = new HealthMetrics(StatsDClient.NO_OP);
    int flushFrequencySeconds = 1;
    Monitoring monitoring = Monitoring.DISABLED;
    boolean traceAgentV05Enabled = Config.get().isTraceAgentV05Enabled();
    boolean metricsReportingEnabled = Config.get().isTracerMetricsEnabled();
    boolean alwaysFlush = false;
    boolean skipRoot = false;

    private DDAgentApi agentApi;
    private Prioritization prioritization;
    private DDAgentFeaturesDiscovery featureDiscovery;

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

    public DDAgentWriterBuilder flushFrequencySeconds(int flushFrequencySeconds) {
      this.flushFrequencySeconds = flushFrequencySeconds;
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

    public DDAgentWriterBuilder alwaysFlush(boolean alwaysFlush) {
      this.alwaysFlush = alwaysFlush;
      return this;
    }

    public DDAgentWriterBuilder skipRoot(boolean skipRoot) {
      this.skipRoot = skipRoot;
      return this;
    }

    public DDAgentWriter build() {
      return new DDAgentWriter(
          agentApi,
          agentHost,
          traceAgentPort,
          unixDomainSocket,
          namedPipe,
          timeoutMillis,
          traceBufferSize,
          healthMetrics,
          flushFrequencySeconds,
          prioritization,
          monitoring,
          traceAgentV05Enabled,
          metricsReportingEnabled,
          featureDiscovery,
          alwaysFlush,
          skipRoot);
    }
  }

  private DDAgentWriter(
      final DDAgentApi agentApi,
      final String agentHost,
      final int traceAgentPort,
      final String unixDomainSocket,
      final String namedPipe,
      final long timeoutMillis,
      final int traceBufferSize,
      final HealthMetrics healthMetrics,
      final int flushFrequencySeconds,
      final Prioritization prioritization,
      final Monitoring monitoring,
      final boolean traceAgentV05Enabled,
      boolean metricsReportingEnabled,
      DDAgentFeaturesDiscovery featureDiscovery,
      final boolean alwaysFlush,
      final boolean skipRoot) {
    HttpUrl agentUrl = HttpUrl.get("http://" + agentHost + ":" + traceAgentPort);
    OkHttpClient client =
        null == featureDiscovery || null == agentApi
            ? buildHttpClient(agentUrl, unixDomainSocket, namedPipe, timeoutMillis)
            : null;
    if (null == featureDiscovery) {
      featureDiscovery =
          new DDAgentFeaturesDiscovery(
              client, monitoring, agentUrl, traceAgentV05Enabled, metricsReportingEnabled);
    }
    if (null == agentApi) {
      api = new DDAgentApi(client, agentUrl, featureDiscovery, monitoring, metricsReportingEnabled);
    } else {
      api = agentApi;
    }
    discovery = featureDiscovery;
    this.healthMetrics = healthMetrics;
    this.dispatcher = new PayloadDispatcher(featureDiscovery, api, healthMetrics, monitoring);
    this.alwaysFlush = alwaysFlush;
    this.skipRoot = skipRoot;
    this.traceProcessingWorker =
        new TraceProcessingWorker(
            traceBufferSize,
            healthMetrics,
            dispatcher,
            featureDiscovery,
            null == prioritization ? FAST_LANE : prioritization,
            flushFrequencySeconds,
            TimeUnit.SECONDS);
  }

  private DDAgentWriter(
      DDAgentFeaturesDiscovery discovery,
      DDAgentApi api,
      HealthMetrics healthMetrics,
      Monitoring monitoring,
      TraceProcessingWorker worker) {
    this.api = api;
    this.discovery = discovery;
    this.healthMetrics = healthMetrics;
    this.traceProcessingWorker = worker;
    this.dispatcher = new PayloadDispatcher(discovery, api, healthMetrics, monitoring);
    this.alwaysFlush = false;
    this.skipRoot = false;
  }

  private DDAgentWriter(
      DDAgentFeaturesDiscovery discovery,
      DDAgentApi api,
      HealthMetrics healthMetrics,
      PayloadDispatcher dispatcher,
      TraceProcessingWorker worker) {
    this.discovery = discovery;
    this.api = api;
    this.healthMetrics = healthMetrics;
    this.traceProcessingWorker = worker;
    this.dispatcher = dispatcher;
    this.alwaysFlush = false;
    this.skipRoot = false;
  }

  public void addResponseListener(final DDAgentResponseListener listener) {
    api.addResponseListener(listener);
  }

  // Exposing some statistics for consumption by monitors
  public final long getCapacity() {
    return traceProcessingWorker.getCapacity();
  }

  @Override
  public void write(final List<DDSpan> trace) {
    // We can't add events after shutdown otherwise it will never complete shutting down.
    if (!closed) {
      if (skipRoot && !trace.isEmpty()) {
        trace.remove(0);
      }
      if (trace.isEmpty()) {
        handleDroppedTrace("Trace was empty", trace);
      } else {
        final DDSpan root = trace.get(0);
        final int samplingPriority = root.context().getSamplingPriority();
        if (traceProcessingWorker.publish(root, samplingPriority, trace)) {
          healthMetrics.onPublish(trace, samplingPriority);
        } else {
          handleDroppedTrace("Trace written to overfilled buffer", trace, samplingPriority);
        }
      }
    } else {
      handleDroppedTrace("Trace written after shutdown.", trace);
    }
    if (alwaysFlush) {
      flush();
    }
  }

  private void handleDroppedTrace(final String reason, final List<DDSpan> trace) {
    log.debug("{}. Counted but dropping trace: {}", reason, trace);
    healthMetrics.onFailedPublish(UNSET);
    incrementDropCounts(trace.size());
  }

  private void handleDroppedTrace(
      final String reason, final List<DDSpan> trace, final int samplingPriority) {
    log.debug("{}. Counted but dropping trace: {}", reason, trace);
    healthMetrics.onFailedPublish(samplingPriority);
    incrementDropCounts(trace.size());
  }

  @Override
  public boolean flush() {
    if (!closed) { // give up after a second
      if (traceProcessingWorker.flush(1, TimeUnit.SECONDS)) {
        healthMetrics.onFlush(false);
        return true;
      }
    }
    return false;
  }

  public DDAgentApi getApi() {
    return api;
  }

  @Override
  public void start() {
    if (!closed) {
      traceProcessingWorker.start();
      healthMetrics.start();
      healthMetrics.onStart((int) getCapacity());
    }
  }

  @Override
  public void close() {
    final boolean flushed = flush();
    closed = true;
    traceProcessingWorker.close();
    healthMetrics.close();
    healthMetrics.onShutdown(flushed);
  }

  @Override
  public void incrementDropCounts(int spanCount) {
    dispatcher.onDroppedTrace(spanCount);
  }
}
