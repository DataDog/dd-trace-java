package datadog.telemetry;

import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Request;

public class TelemetryRequest {
  private final EventSource eventSource;
  private final EventSink eventSink;
  private final long messageBytesSoftLimit;
  private final TelemetryRequestState telemetryRequestState;

  public TelemetryRequest(
      EventSource eventSource,
      EventSink eventSink,
      long messageBytesSoftLimit,
      RequestType requestType,
      HttpUrl httpUrl,
      boolean debug) {
    this.eventSource = eventSource;
    this.eventSink = eventSink;
    this.messageBytesSoftLimit = messageBytesSoftLimit;
    this.telemetryRequestState = new TelemetryRequestState(requestType, httpUrl, debug);
    this.telemetryRequestState.beginRequest();
  }

  public Request httpRequest() {
    return telemetryRequestState.endRequest();
  }

  public void writeConfigurationMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasConfigChangeEvent()) {
      return;
    }
    telemetryRequestState.beginMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
    telemetryRequestState.beginSinglePayload();
    writeConfigurations();
    telemetryRequestState.endSinglePayload();
    telemetryRequestState.endMessage();
  }

  public void writeConfigurations() {
    if (!eventSource.hasConfigChangeEvent()) {
      return;
    }
    try {
      telemetryRequestState.beginConfiguration();
      while (eventSource.hasConfigChangeEvent() && isWithinSizeLimits()) {
        ConfigSetting event = eventSource.nextConfigChangeEvent();
        telemetryRequestState.writeConfiguration(event);
        eventSink.addConfigChangeEvent(event);
      }
      telemetryRequestState.endConfiguration();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("configuration-object", e);
    }
  }

  public void writeProducts() {
    InstrumenterConfig instrumenterConfig = InstrumenterConfig.get();
    try {
      boolean appsecEnabled =
          instrumenterConfig.getAppSecActivation() != ProductActivation.FULLY_DISABLED;
      boolean profilerEnabled = instrumenterConfig.isProfilingEnabled();
      telemetryRequestState.writeProducts(appsecEnabled, profilerEnabled);
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("products", e);
    }
  }

  public void writeIntegrationsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasIntegrationEvent()) {
      return;
    }
    try {
      telemetryRequestState.beginIntegrations();
      while (eventSource.hasIntegrationEvent() && isWithinSizeLimits()) {
        Integration event = eventSource.nextIntegrationEvent();
        telemetryRequestState.writeIntegration(event);
        eventSink.addIntegrationEvent(event);
      }
      telemetryRequestState.endIntegrations();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("integrations-message", e);
    }
  }

  public void writeDependenciesMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasDependencyEvent()) {
      return;
    }
    try {
      telemetryRequestState.beginDependencies();
      while (eventSource.hasDependencyEvent() && isWithinSizeLimits()) {
        Dependency event = eventSource.nextDependencyEvent();
        telemetryRequestState.writeDependency(event);
        eventSink.addDependencyEvent(event);
      }
      telemetryRequestState.endDependencies();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("dependencies-message", e);
    }
  }

  public void writeMetricsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasMetricEvent()) {
      return;
    }
    try {
      telemetryRequestState.beginMetrics();
      while (eventSource.hasMetricEvent() && isWithinSizeLimits()) {
        Metric event = eventSource.nextMetricEvent();
        telemetryRequestState.writeMetric(event);
        eventSink.addMetricEvent(event);
      }
      telemetryRequestState.endMetrics();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("metrics-message", e);
    }
  }

  public void writeDistributionsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasDistributionSeriesEvent()) {
      return;
    }
    try {
      telemetryRequestState.beginDistributions();
      while (eventSource.hasDistributionSeriesEvent() && isWithinSizeLimits()) {
        DistributionSeries event = eventSource.nextDistributionSeriesEvent();
        telemetryRequestState.writeDistribution(event);
        eventSink.addDistributionSeriesEvent(event);
      }
      telemetryRequestState.endDistributions();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("distributions-message", e);
    }
  }

  public void writeLogsMessage() {
    if (!isWithinSizeLimits() || !eventSource.hasLogMessageEvent()) {
      return;
    }
    try {
      telemetryRequestState.beginLogs();
      while (eventSource.hasLogMessageEvent() && isWithinSizeLimits()) {
        LogMessage event = eventSource.nextLogMessageEvent();
        telemetryRequestState.writeLog(event);
        eventSink.addLogMessageEvent(event);
      }
      telemetryRequestState.endLogs();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("logs-message", e);
    }
  }

  private boolean isWithinSizeLimits() {
    return telemetryRequestState.size() < messageBytesSoftLimit;
  }

  public void writeHeartbeatEvent() {
    telemetryRequestState.writeHeartbeatEvent();
  }
}
