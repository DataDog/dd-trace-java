package datadog.telemetry;

import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.TracerVersion;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.DDTags;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import java.io.IOException;
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

  TelemetryRequest(
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

  public Request.Builder httpRequest() {
    long bodySize = requestBody.endRequest();

    Request.Builder builder =
        new Request.Builder()
            .addHeader("Content-Type", String.valueOf(JSON))
            .addHeader("Content-Length", String.valueOf(bodySize))
            .addHeader("DD-Telemetry-API-Version", API_VERSION)
            .addHeader("DD-Telemetry-Request-Type", String.valueOf(this.requestType))
            .addHeader("DD-Client-Library-Language", DDTags.LANGUAGE_TAG_VALUE)
            .addHeader("DD-Client-Library-Version", TracerVersion.TRACER_VERSION)
            .post(requestBody);

    final String containerId = ContainerInfo.get().getContainerId();
    if (containerId != null) {
      builder.addHeader("Datadog-Container-ID", containerId);
    }
    final String entityId = ContainerInfo.getEntityId();
    if (entityId != null) {
      builder.addHeader("Datadog-Entity-ID", entityId);
    }

    if (debug) {
      builder.addHeader("DD-Telemetry-Debug-Enabled", "true");
    }

    return builder;
  }

  public void writeConfigurations() {
    if (!isWithinSizeLimits() || !eventSource.hasConfigChangeEvent()) {
      return;
    }
    try {
      requestBody.beginConfiguration();
      while (eventSource.hasConfigChangeEvent() && isWithinSizeLimits()) {
        ConfigSetting event = eventSource.nextConfigChangeEvent();
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
    Config config = Config.get();
    try {
      boolean appsecEnabled =
          instrumenterConfig.getAppSecActivation() != ProductActivation.FULLY_DISABLED;
      boolean profilerEnabled = instrumenterConfig.isProfilingEnabled();
      boolean dynamicInstrumentationEnabled = config.isDebuggerEnabled();
      requestBody.writeProducts(appsecEnabled, profilerEnabled, dynamicInstrumentationEnabled);
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("products", e);
    }
  }

  public void writeInstallSignature() {
    String installId = System.getenv("DD_INSTRUMENTATION_INSTALL_ID");
    String installType = System.getenv("DD_INSTRUMENTATION_INSTALL_TYPE");
    String installTime = System.getenv("DD_INSTRUMENTATION_INSTALL_TIME");

    try {
      requestBody.writeInstallSignature(installId, installType, installTime);
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("install-signature", e);
    }
  }

  public void writeIntegrations() {
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

  public void writeDependencies() {
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

  public void writeMetrics() {
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

  public void writeDistributions() {
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

  public void writeLogs() {
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

  public void writeHeartbeat() {
    requestBody.writeHeartbeatEvent();
  }

  private boolean isWithinSizeLimits() {
    return requestBody.size() < messageBytesSoftLimit;
  }
}
