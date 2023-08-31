package datadog.telemetry;

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
      while (eventSource.hasConfigChangeEvent()) {
        ConfigChange event = eventSource.nextConfigChangeEvent();
        telemetryRequestState.writeConfiguration(event);
        eventSink.addConfigChangeEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
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
    if (!isWithinSizeLimits()) {
      return;
    }
    Integration event = eventSource.nextIntegrationEvent();
    if (event == null) {
      return;
    }
    try {
      telemetryRequestState.beginIntegrations();
      while (event != null) {
        telemetryRequestState.writeIntegration(event);
        eventSink.addIntegrationEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextIntegrationEvent();
      }
      telemetryRequestState.endIntegrations();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("integrations-message", e);
    }
  }

  public void writeDependenciesMessage() {
    if (!isWithinSizeLimits()) {
      return;
    }
    Dependency event = eventSource.nextDependencyEvent();
    if (event == null) {
      return;
    }
    try {
      telemetryRequestState.beginDependencies();
      while (event != null) {
        telemetryRequestState.writeDependency(event);
        eventSink.addDependencyEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextDependencyEvent();
      }
      telemetryRequestState.endDependencies();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("dependencies-message", e);
    }
  }

  public void writeMetricsMessage() {
    if (!isWithinSizeLimits()) {
      return;
    }
    Metric event = eventSource.nextMetricEvent();
    if (event == null) {
      return;
    }
    try {
      telemetryRequestState.beginMetrics();
      while (event != null) {
        telemetryRequestState.writeMetric(event);
        eventSink.addMetricEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextMetricEvent();
      }
      telemetryRequestState.endMetrics();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("metrics-message", e);
    }
  }

  public void writeDistributionsMessage() {
    if (!isWithinSizeLimits()) {
      return;
    }
    DistributionSeries event = eventSource.nextDistributionSeriesEvent();
    if (event == null) {
      return;
    }
    try {
      telemetryRequestState.beginDistributions();
      while (event != null) {
        telemetryRequestState.writeDistribution(event);
        eventSink.addDistributionSeriesEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextDistributionSeriesEvent();
      }
      telemetryRequestState.endDistributions();
    } catch (IOException e) {
      throw new TelemetryRequestState.SerializationException("distributions-message", e);
    }
  }

  public void writeLogsMessage() {
    if (!isWithinSizeLimits()) {
      return;
    }
    LogMessage event = eventSource.nextLogMessageEvent();
    if (event == null) {
      return;
    }
    try {
      telemetryRequestState.beginLogs();
      while (event != null) {
        telemetryRequestState.writeLog(event);
        eventSink.addLogMessageEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextLogMessageEvent();
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
