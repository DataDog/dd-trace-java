package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.ProductChange;
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

  private final BlockingQueue<ProductChange> productChanges = new LinkedBlockingQueue<>();

  private final ExtendedHeartbeatData extendedHeartbeatData = new ExtendedHeartbeatData();

  private final BlockingQueue<Endpoint> endpoints = new LinkedBlockingQueue<>();

  private final EventSource.Queued eventSource =
      new EventSource.Queued(
          configurations,
          integrations,
          dependencies,
          metrics,
          distributionSeries,
          logMessages,
          productChanges,
          endpoints);

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
      boolean useIntakeClientByDefault,
      boolean debug) {
    TelemetryRouter telemetryRouter =
        new TelemetryRouter(
            ddAgentFeaturesDiscovery, agentClient, intakeClient, useIntakeClientByDefault);
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

  public boolean addConfiguration(Map<ConfigOrigin, Map<String, ConfigSetting>> configuration) {
    for (Map<String, ConfigSetting> settings : configuration.values()) {
      for (ConfigSetting cs : settings.values()) {
        extendedHeartbeatData.pushConfigSetting(cs);
        if (!this.configurations.offer(cs)) {
          return false;
        }
      }
    }
    return true;
  }

  public boolean addDependency(Dependency dependency) {
    extendedHeartbeatData.pushDependency(dependency);
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
    extendedHeartbeatData.pushIntegration(integration);
    return this.integrations.offer(integration);
  }

  public boolean addMetric(Metric metric) {
    return this.metrics.offer(metric);
  }

  public boolean addLogMessage(LogMessage message) {
    return this.logMessages.offer(message);
  }

  public boolean addProductChange(ProductChange productChange) {
    return this.productChanges.offer(productChange);
  }

  public boolean addDistributionSeries(DistributionSeries series) {
    return this.distributionSeries.offer(series);
  }

  public boolean addEndpoint(final Endpoint endpoint) {
    return this.endpoints.offer(endpoint);
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

  /**
   * @return true - if an app-started event has been successfully sent, false - otherwise
   */
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
    TelemetryRequest request =
        new TelemetryRequest(
            eventSource, eventSink, messageBytesSoftLimit, RequestType.APP_STARTED, debug);

    request.writeProducts();
    request.writeConfigurations();
    request.writeInstallSignature();
    if (telemetryRouter.sendRequest(request) == TelemetryClient.Result.SUCCESS) {
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
    TelemetryRequest request;
    boolean isMoreDataAvailable = false;
    if (eventSource.isEmpty()) {
      log.debug("Preparing app-heartbeat request");
      request =
          new TelemetryRequest(
              eventSource, eventSink, messageBytesSoftLimit, RequestType.APP_HEARTBEAT, debug);
    } else {
      log.debug("Preparing message-batch request");
      request =
          new TelemetryRequest(
              eventSource, eventSink, messageBytesSoftLimit, RequestType.MESSAGE_BATCH, debug);
      request.writeHeartbeat();
      request.writeConfigurations();
      request.writeIntegrations();
      request.writeDependencies();
      request.writeMetrics();
      request.writeDistributions();
      request.writeLogs();
      request.writeChangedProducts();
      request.writeEndpoints();
      isMoreDataAvailable = !this.eventSource.isEmpty();
    }

    TelemetryClient.Result result = telemetryRouter.sendRequest(request);
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

  /**
   * @return true - if extended heartbeat request sent successfully, otherwise false
   */
  public boolean sendExtendedHeartbeat() {
    log.debug("Preparing message-batch request");
    EventSource extendedHeartbeatDataSnapshot = extendedHeartbeatData.snapshot();
    TelemetryRequest request =
        new TelemetryRequest(
            extendedHeartbeatDataSnapshot,
            EventSink.NOOP,
            messageBytesSoftLimit,
            RequestType.APP_EXTENDED_HEARTBEAT,
            debug);
    request.writeConfigurations();
    request.writeDependencies();
    request.writeIntegrations();

    TelemetryClient.Result result = telemetryRouter.sendRequest(request);
    if (!extendedHeartbeatDataSnapshot.isEmpty()) {
      log.warn("Telemetry Extended Heartbeat data does NOT fit in one request.");
    }
    return result == TelemetryClient.Result.SUCCESS;
  }

  void warnAboutExclusiveIntegrations() {
    log.warn(
        "Both OpenTracing and OpenTelemetry integrations are enabled but mutually exclusive. Tracing performance can be degraded.");
  }
}
