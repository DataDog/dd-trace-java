package datadog.telemetry;

import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.TracerVersion;
import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;

public class TelemetryRequest {
  static final String API_VERSION = "v2";
  static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private final EventSource eventSource;
  private final EventSink eventSink;
  private final long messageBytesSoftLimit;
  private final RequestType requestType;
  private final boolean debug;
  private final TelemetryRequestBody requestBody;

  public TelemetryRequest(
      EventSource eventSource,
      EventSink eventSink,
      long messageBytesSoftLimit,
      RequestType requestType,
      boolean debug) {
    this.eventSource = eventSource;
    this.eventSink = eventSink;
    this.messageBytesSoftLimit = messageBytesSoftLimit;
    this.requestType = requestType;
    this.debug = debug;
    this.requestBody = new TelemetryRequestBody(requestType);
    this.requestBody.beginRequest(debug);
  }

  public Request httpRequest(HttpUrl url, String apiKey) {
    long bodySize = requestBody.endRequest();

    Request.Builder builder =
        new Request.Builder()
            .addHeader("Content-Type", String.valueOf(JSON))
            .addHeader("Content-Length", String.valueOf(bodySize))
            .addHeader("DD-Telemetry-API-Version", API_VERSION)
            .addHeader("DD-Telemetry-Request-Type", String.valueOf(this.requestType))
            .addHeader("DD-Client-Library-Language", "jvm")
            .addHeader("DD-Client-Library-Version", TracerVersion.TRACER_VERSION)
            .post(requestBody)
            .url(url);

    final String containerId = ContainerInfo.get().getContainerId();
    if (containerId != null) {
      builder.addHeader("Datadog-Container-ID", containerId);
    }

    if (debug) {
      builder.addHeader("DD-Telemetry-Debug-Enabled", "true");
    }

    if (apiKey != null) {
      builder.addHeader("DD-API-KEY", apiKey);
      //    TODO  OkHttpUtils.addApiKey(config, requestBuilder)
    }

    return builder.build();
  }

  public void writeConfigurationMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasConfigChangeEvent()) {
      return;
    }
    requestBody.beginMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
    requestBody.beginSinglePayload();
    writeConfigurations();
    requestBody.endSinglePayload();
    requestBody.endMessage();
  }

  public void writeConfigurations() {
    if (!eventSource.hasConfigChangeEvent()) {
      return;
    }
    try {
      requestBody.beginConfiguration();
      while (eventSource.hasConfigChangeEvent() && isWithinSizeLimits()) {
        ConfigChange event = eventSource.nextConfigChangeEvent();
        requestBody.writeConfiguration(event);
        eventSink.addConfigChangeEvent(event);
      }
      requestBody.endConfiguration();
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("configuration-object", e);
    }
  }

  public void writeProducts() {
    InstrumenterConfig instrumenterConfig = InstrumenterConfig.get();
    try {
      boolean appsecEnabled =
          instrumenterConfig.getAppSecActivation() != ProductActivation.FULLY_DISABLED;
      boolean profilerEnabled = instrumenterConfig.isProfilingEnabled();
      requestBody.writeProducts(appsecEnabled, profilerEnabled);
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("products", e);
    }
  }

  public void writeIntegrationsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasIntegrationEvent()) {
      return;
    }
    try {
      requestBody.beginIntegrations();
      while (eventSource.hasIntegrationEvent() && isWithinSizeLimits()) {
        Integration event = eventSource.nextIntegrationEvent();
        requestBody.writeIntegration(event);
        eventSink.addIntegrationEvent(event);
      }
      requestBody.endIntegrations();
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("integrations-message", e);
    }
  }

  public void writeDependenciesMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasDependencyEvent()) {
      return;
    }
    try {
      requestBody.beginDependencies();
      while (eventSource.hasDependencyEvent() && isWithinSizeLimits()) {
        Dependency event = eventSource.nextDependencyEvent();
        requestBody.writeDependency(event);
        eventSink.addDependencyEvent(event);
      }
      requestBody.endDependencies();
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("dependencies-message", e);
    }
  }

  public void writeMetricsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasMetricEvent()) {
      return;
    }
    try {
      requestBody.beginMetrics();
      while (eventSource.hasMetricEvent() && isWithinSizeLimits()) {
        Metric event = eventSource.nextMetricEvent();
        requestBody.writeMetric(event);
        eventSink.addMetricEvent(event);
      }
      requestBody.endMetrics();
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("metrics-message", e);
    }
  }

  public void writeDistributionsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasDistributionSeriesEvent()) {
      return;
    }
    try {
      requestBody.beginDistributions();
      while (eventSource.hasDistributionSeriesEvent() && isWithinSizeLimits()) {
        DistributionSeries event = eventSource.nextDistributionSeriesEvent();
        requestBody.writeDistribution(event);
        eventSink.addDistributionSeriesEvent(event);
      }
      requestBody.endDistributions();
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("distributions-message", e);
    }
  }

  public void writeLogsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasLogMessageEvent()) {
      return;
    }
    try {
      requestBody.beginLogs();
      while (eventSource.hasLogMessageEvent() && isWithinSizeLimits()) {
        LogMessage event = eventSource.nextLogMessageEvent();
        requestBody.writeLog(event);
        eventSink.addLogMessageEvent(event);
      }
      requestBody.endLogs();
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("logs-message", e);
    }
  }

  private boolean isWithinSizeLimits() {
    return requestBody.size() < messageBytesSoftLimit;
  }

  public void writeHeartbeatEvent() {
    requestBody.writeHeartbeatEvent();
  }
}
