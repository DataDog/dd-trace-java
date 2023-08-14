package datadog.telemetry;

import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Request;

public class BatchRequestBuilder {
  private final EventSource eventSource;
  private final EventSink eventSink;
  private final long messageBytesSoftLimit;
  private RequestBuilder requestBuilder;

  public BatchRequestBuilder(
      EventSource eventSource, EventSink eventSink, long messageBytesSoftLimit) {
    this.eventSource = eventSource;
    this.eventSink = eventSink;
    this.messageBytesSoftLimit = messageBytesSoftLimit;
  }

  public void beginRequest(RequestType requestType, HttpUrl httpUrl) {
    if (requestBuilder != null) {
      throw new IllegalStateException("Request already started!");
    }
    requestBuilder = new RequestBuilder(requestType, httpUrl);
    requestBuilder.beginRequest();
  }

  public Request endRequest() {
    if (requestBuilder == null) {
      throw new IllegalStateException("Request not started!");
    }
    requestBuilder.endRequest();
    Request request = requestBuilder.request();
    requestBuilder = null;
    return request;
  }

  public void writeConfigurationMessage() {
    if (!isWithinSizeLimits()) {
      return;
    }
    ConfigChange event = eventSource.nextConfigChangeEvent();
    if (event == null) {
      return;
    }
    try {
      requestBuilder.beginMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
      requestBuilder.beginPayload();
      requestBuilder.beginConfiguration();
      while (event != null) {
        requestBuilder.writeConfiguration(event);
        eventSink.addConfigChangeEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextConfigChangeEvent();
      }
      requestBuilder.endConfiguration();
      requestBuilder.endPayload();
      requestBuilder.endMessage();
    } catch (IOException e) {
      throw new RequestBuilder.SerializationException("configuration-message", e);
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
      requestBuilder.beginIntegrations();
      while (event != null) {
        requestBuilder.writeIntegration(event);
        eventSink.addIntegrationEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextIntegrationEvent();
      }
      requestBuilder.endIntegrations();
    } catch (IOException e) {
      throw new RequestBuilder.SerializationException("integrations-message", e);
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
      requestBuilder.beginDependencies();
      while (event != null) {
        requestBuilder.writeDependency(event);
        eventSink.addDependencyEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextDependencyEvent();
      }
      requestBuilder.endDependencies();
    } catch (IOException e) {
      throw new RequestBuilder.SerializationException("dependencies-message", e);
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
      requestBuilder.beginMetrics();
      while (event != null) {
        requestBuilder.writeMetric(event);
        eventSink.addMetricEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextMetricEvent();
      }
      requestBuilder.endMetrics();
    } catch (IOException e) {
      throw new RequestBuilder.SerializationException("metrics-message", e);
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
      requestBuilder.beginDistributions();
      while (event != null) {
        requestBuilder.writeDistribution(event);
        eventSink.addDistributionSeriesEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextDistributionSeriesEvent();
      }
      requestBuilder.endDistributions();
    } catch (IOException e) {
      throw new RequestBuilder.SerializationException("distributions-message", e);
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
      requestBuilder.beginLogs();
      while (event != null) {
        requestBuilder.writeLog(event);
        eventSink.addLogMessageEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
        event = eventSource.nextLogMessageEvent();
      }
      requestBuilder.endLogs();
    } catch (IOException e) {
      throw new RequestBuilder.SerializationException("logs-message", e);
    }
  }

  private boolean isWithinSizeLimits() {
    return requestBuilder.size() < messageBytesSoftLimit;
  }

  public void writeHeartbeatEvent() {
    requestBuilder.writeHeartbeatEvent();
  }
}
