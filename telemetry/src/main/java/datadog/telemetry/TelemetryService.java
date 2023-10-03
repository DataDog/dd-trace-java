package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigSetting;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

  private static final long DEFAULT_MESSAGE_BYTES_SOFT_LIMIT = Math.round(5 * 1024 * 1024 * 0.75);

  private final TelemetryRouter telemetryRouter;
  private final BlockingQueue<ConfigSetting> configurations = new LinkedBlockingQueue<>();
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

  private final long messageBytesSoftLimit;
  private final boolean debug;

  /*
   * Keep track of Open Tracing and Open Telemetry integrations activation as they are mutually exclusive.
   */
  private boolean openTracingIntegrationEnabled;
  private boolean openTelemetryIntegrationEnabled;

  public static TelemetryService build(
      DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery,
      TelemetryClient agentClient,
      TelemetryClient intakeClient,
      boolean debug) {
    TelemetryRouter telemetryRouter =
        new TelemetryRouter(ddAgentFeaturesDiscovery, agentClient, intakeClient);
    return new TelemetryService(telemetryRouter, DEFAULT_MESSAGE_BYTES_SOFT_LIMIT, debug);
  }

  // For testing purposes
  TelemetryService(
      final TelemetryRouter telemetryRouter,
      final long messageBytesSoftLimit,
      final boolean debug) {
    this.telemetryRouter = telemetryRouter;
    this.openTracingIntegrationEnabled = false;
    this.openTelemetryIntegrationEnabled = false;
    this.messageBytesSoftLimit = messageBytesSoftLimit;
    this.debug = debug;
  }

  public boolean addConfiguration(Map<String, ConfigSetting> configuration) {
    for (ConfigSetting configSetting : configuration.values()) {
      if (!this.configurations.offer(configSetting)) {
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
            debug);
    if (telemetryRouter.sendRequest(telemetryRequest) != TelemetryClient.Result.SUCCESS) {
      log.warn("Couldn't send app-closing event!");
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
            eventSource, eventSink, messageBytesSoftLimit, RequestType.APP_STARTED, debug);
    telemetryRequest.writeProducts();
    telemetryRequest.writeConfigurations();
    if (telemetryRouter.sendRequest(telemetryRequest) == TelemetryClient.Result.SUCCESS) {
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
              eventSource, eventSink, messageBytesSoftLimit, RequestType.APP_HEARTBEAT, debug);
    } else {
      log.debug("Preparing message-batch request");
      telemetryRequest =
          new TelemetryRequest(
              eventSource, eventSink, messageBytesSoftLimit, RequestType.MESSAGE_BATCH, debug);
      telemetryRequest.writeHeartbeatEvent();
      telemetryRequest.writeConfigurationMessage();
      telemetryRequest.writeIntegrationsMessage();
      telemetryRequest.writeDependenciesMessage();
      telemetryRequest.writeMetricsMessage();
      telemetryRequest.writeDistributionsMessage();
      telemetryRequest.writeLogsMessage();
      isMoreDataAvailable = !this.eventSource.isEmpty();
    }

    TelemetryClient.Result result = telemetryRouter.sendRequest(telemetryRequest);
    if (result == TelemetryClient.Result.SUCCESS) {
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
