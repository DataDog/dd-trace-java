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

  private static final long DEFAULT_MESSAGE_BYTES_SOFT_LIMIT = Math.round(5 * 1024 * 1024 * 0.75);

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
  private final long messageBytesSoftLimit;
  private final boolean debug;

  /*
   * Keep track of Open Tracing and Open Telemetry integrations activation as they are mutually exclusive.
   */
  private boolean openTracingIntegrationEnabled;
  private boolean openTelemetryIntegrationEnabled;

  /**
   * @param okHttpClient - an instance to do http calls
   * @param httpUrl - telemetry endpoint URL
   * @param debug - when `true` it adds a debug flag to a telemetry request to handle it on the
   *     backend with verbose logging
   */
  public TelemetryService(
      final OkHttpClient okHttpClient, final HttpUrl httpUrl, final boolean debug) {
    this(new HttpClient(okHttpClient), httpUrl, DEFAULT_MESSAGE_BYTES_SOFT_LIMIT, debug);
  }

  // For testing purposes
  TelemetryService(
      final HttpClient httpClient,
      final HttpUrl agentUrl,
      final long messageBytesSoftLimit,
      final boolean debug) {
    this.httpClient = httpClient;
    this.openTracingIntegrationEnabled = false;
    this.openTelemetryIntegrationEnabled = false;
    this.httpUrl = agentUrl.newBuilder().addPathSegments(API_ENDPOINT).build();
    this.messageBytesSoftLimit = messageBytesSoftLimit;
    this.debug = debug;
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
    TelemetryRequest telemetryRequest =
        new TelemetryRequest(
            this.eventSource,
            EventSink.NOOP,
            messageBytesSoftLimit,
            RequestType.APP_CLOSING,
            httpUrl,
            debug);
    Request request = telemetryRequest.httpRequest();
    if (httpClient.sendRequest(request) != HttpClient.Result.SUCCESS) {
      log.error("Couldn't send app-closing event!");
    }
  }

  // keeps track of unsent events from the previous attempt
  private BufferedEvents bufferedEvents;

  /** @return true - if an app-started event has been successfully sent, false - otherwise */
  public boolean sendAppStartedEvent() {
    EventSource eventSource;
    EventSink eventSink;
    if (bufferedEvents == null) {
      eventSource = this.eventSource;
    } else {
      log.debug(
          "Sending buffered telemetry events that couldn't have been sent on previous attempt");
      eventSource = bufferedEvents;
    }
    // use a buffer as a sink, so we can retry on the next attempt in case of a request failure
    bufferedEvents = new BufferedEvents();
    eventSink = bufferedEvents;

    log.debug("Preparing app-started request");
    TelemetryRequest telemetryRequest =
        new TelemetryRequest(
            eventSource, eventSink, messageBytesSoftLimit, RequestType.APP_STARTED, httpUrl, debug);
    telemetryRequest.writeProducts();
    telemetryRequest.writeConfigurations();
    Request request = telemetryRequest.httpRequest();

    if (httpClient.sendRequest(request) == HttpClient.Result.SUCCESS) {
      // discard already sent buffered event on the successful attempt
      bufferedEvents = null;
      return true;
    }
    return false;
  }

  /**
   * @return true - only part of data has been sent because of the request size limit false - all
   *     data has been sent, or it has failed sending a request
   */
  public boolean sendTelemetryEvents() {
    EventSource eventSource;
    EventSink eventSink;
    if (bufferedEvents == null) {
      log.debug("Sending telemetry events");
      eventSource = this.eventSource;
      // use a buffer as a sink, so we can retry on the next attempt in case of a request failure
      bufferedEvents = new BufferedEvents();
      eventSink = bufferedEvents;
    } else {
      log.debug(
          "Sending buffered telemetry events that couldn't have been sent on previous attempt");
      eventSource = bufferedEvents;
      eventSink = EventSink.NOOP; // TODO collect metrics for unsent events
    }
    TelemetryRequest telemetryRequest;
    boolean isMoreDataAvailable = false;
    if (eventSource.isEmpty()) {
      log.debug("Preparing app-heartbeat request");
      telemetryRequest =
          new TelemetryRequest(
              eventSource,
              eventSink,
              messageBytesSoftLimit,
              RequestType.APP_HEARTBEAT,
              httpUrl,
              debug);
    } else {
      log.debug("Preparing message-batch request");
      telemetryRequest =
          new TelemetryRequest(
              eventSource,
              eventSink,
              messageBytesSoftLimit,
              RequestType.MESSAGE_BATCH,
              httpUrl,
              debug);
      telemetryRequest.writeHeartbeatEvent();
      telemetryRequest.writeConfigurationMessage();
      telemetryRequest.writeIntegrationsMessage();
      telemetryRequest.writeDependenciesMessage();
      telemetryRequest.writeMetricsMessage();
      telemetryRequest.writeDistributionsMessage();
      telemetryRequest.writeLogsMessage();
      isMoreDataAvailable = !this.eventSource.isEmpty();
    }
    Request request = telemetryRequest.httpRequest();

    HttpClient.Result result = httpClient.sendRequest(request);
    if (result == HttpClient.Result.SUCCESS) {
      log.debug("Telemetry request has been sent successfully.");
      bufferedEvents = null;
      return isMoreDataAvailable;
    } else {
      log.debug("Telemetry request has failed: {}", result);
      if (eventSource == bufferedEvents) {
        // TODO report metrics for discarded events
        bufferedEvents = null;
      }
    }
    return false;
  }

  void warnAboutExclusiveIntegrations() {
    log.warn(
        "Both OpenTracing and OpenTelemetry integrations are enabled but mutually exclusive. Tracing performance can be degraded.");
  }
}
