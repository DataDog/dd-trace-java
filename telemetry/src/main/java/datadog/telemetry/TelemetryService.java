package datadog.telemetry;

import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

  private static final String API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";

  private final HttpClient httpClient;
  private final BlockingQueue<ConfigChange> configurations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Integration> integrations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Dependency> dependencies = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metric> metrics =
      new LinkedBlockingQueue<>(1024); // recommended capacity?

  private final BlockingQueue<LogMessage> logMessages = new LinkedBlockingQueue<>(1024);

  private final BlockingQueue<DistributionSeries> distributionSeries =
      new LinkedBlockingQueue<>(1024);

  private final EventSource.Queued eventSource =
      new EventSource.Queued(
          configurations, integrations, dependencies, metrics, distributionSeries, logMessages);

  private final HttpUrl httpUrl;

  private boolean sentAppStarted;

  /*
   * Keep track of Open Tracing and Open Telemetry integrations activation as they are mutually exclusive.
   */
  private boolean openTracingIntegrationEnabled;
  private boolean openTelemetryIntegrationEnabled;

  public TelemetryService(final OkHttpClient okHttpClient, final HttpUrl httpUrl) {
    this(new HttpClient(okHttpClient), httpUrl);
  }

  // For testing purposes
  TelemetryService(final HttpClient httpClient, final HttpUrl agentUrl) {
    this.httpClient = httpClient;
    this.sentAppStarted = false;
    this.openTracingIntegrationEnabled = false;
    this.openTelemetryIntegrationEnabled = false;
    this.httpUrl = agentUrl.newBuilder().addPathSegments(API_ENDPOINT).build();
  }

  public boolean addConfiguration(Map<String, Object> configuration) {
    for (Map.Entry<String, Object> entry : configuration.entrySet()) {
      if (!this.configurations.offer(new ConfigChange(entry.getKey(), entry.getValue()))) {
        return false;
      }
    }
    return true;
  }

  public boolean addDependency(Dependency dependency) {
    return this.dependencies.offer(dependency);
  }

  public boolean addIntegration(Integration integration) {
    if ("opentelemetry-1".equals(integration.name)) {
      openTelemetryIntegrationEnabled = integration.enabled;
    }
    if ("opentracing".equals(integration.name)) {
      openTracingIntegrationEnabled = integration.enabled;
    }
    if (openTelemetryIntegrationEnabled && openTracingIntegrationEnabled) {
      warnAboutExclusiveIntegrations();
    }
    return this.integrations.offer(integration);
  }

  public boolean addMetric(Metric metric) {
    return this.metrics.offer(metric);
  }

  public boolean addLogMessage(LogMessage message) {
    // TODO doesn't seem to be used
    return this.logMessages.offer(message);
  }

  public boolean addDistributionSeries(DistributionSeries series) {
    // TODO doesn't seem to be used
    return this.distributionSeries.offer(series);
  }

  public void sendAppClosingEvent() {
    BatchRequestBuilder batchRequestBuilder =
        new BatchRequestBuilder(EventSource.noop(), EventSink.noop());
    batchRequestBuilder.beginRequest(RequestType.APP_CLOSING, httpUrl);
    Request request = batchRequestBuilder.endRequest();
    // TODO include metrics and other payloads
    if (httpClient.sendRequest(request) != HttpClient.Result.SUCCESS) {
      // TODO log error
    }
  }

  // keeps track of unsent events from the previous attempt
  private BufferedEvents bufferedEvents;

  public void sendTelemetryEvents() {
    EventSource eventSource;
    EventSink eventSink;
    if (bufferedEvents == null) {
      // build a telemetry request from the event queues
      eventSource = this.eventSource;
      // use a buffer, so we can retry on the next attempt in case of a request failure
      bufferedEvents = new BufferedEvents();
      eventSink = bufferedEvents;
    } else {
      // retry sending unsent buffered events from the last attempt
      eventSource = bufferedEvents;
      eventSink = EventSink.noop(); // TODO add metrics for unsent events
    }
    BatchRequestBuilder batchRequestBuilder = new BatchRequestBuilder(eventSource, eventSink);

    Request request;
    if (!sentAppStarted) {
      batchRequestBuilder.beginRequest(RequestType.APP_STARTED, httpUrl);
      batchRequestBuilder.writeConfigurationMessage();
      request = batchRequestBuilder.endRequest();
    } else if (eventSource.isEmpty()) {
      batchRequestBuilder.beginRequest(RequestType.APP_HEARTBEAT, httpUrl);
      request = batchRequestBuilder.endRequest();
    } else {
      batchRequestBuilder.beginRequest(RequestType.MESSAGE_BATCH, httpUrl);
      batchRequestBuilder.writeHeartbeatEvent();
      batchRequestBuilder.writeConfigurationMessage();
      batchRequestBuilder.writeIntegrationsMessage();
      batchRequestBuilder.writeDependenciesMessage();
      batchRequestBuilder.writeMetricsMessage();
      batchRequestBuilder.writeDistributionsMessage();
      batchRequestBuilder.writeLogsMessage();
      request = batchRequestBuilder.endRequest();
    }

    HttpClient.Result result = httpClient.sendRequest(request);
    if (result == HttpClient.Result.SUCCESS) {
      sentAppStarted = true;
      bufferedEvents = null;
      if (!this.eventSource.isEmpty()) {
        // if the events have been successfully sent then do another request immediately with new
        // events if any
        // TODO maybe limit number of requests
        sendTelemetryEvents();
      }
    }
  }

  void warnAboutExclusiveIntegrations() {
    log.warn(
        "Both OpenTracing and OpenTelemetry integrations are enabled but mutually exclusive. Tracing performance can be degraded.");
  }
}
