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
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.ProductChange;
import datadog.trace.api.telemetry.ProductChange.ProductType;
import datadog.trace.config.inversion.ConfigHelper;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryRequest {

  private static final Logger log = LoggerFactory.getLogger(TelemetryRequest.class);

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
    try {
      requestBody.writeProducts(
          InstrumenterConfig.get().getAppSecActivation() != ProductActivation.FULLY_DISABLED,
          InstrumenterConfig.get().isProfilingEnabled(),
          Config.get().isDynamicInstrumentationEnabled());
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("products", e);
    }
  }

  public void writeInstallSignature() {
    String installId = ConfigHelper.env("DD_INSTRUMENTATION_INSTALL_ID");
    String installType = ConfigHelper.env("DD_INSTRUMENTATION_INSTALL_TYPE");
    String installTime = ConfigHelper.env("DD_INSTRUMENTATION_INSTALL_TIME");

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

  public void writeChangedProducts() {
    if (!isWithinSizeLimits() || !eventSource.hasProductChangeEvent()) {
      return;
    }
    try {
      log.debug("Writing changed products");
      requestBody.beginProducts();
      Map<ProductType, Boolean> products = new EnumMap<>(ProductType.class);
      while (eventSource.hasProductChangeEvent() && isWithinSizeLimits()) {
        ProductChange event = eventSource.nextProductChangeEvent();
        products.put(event.getProductType(), event.isEnabled());
        eventSink.addProductChangeEvent(event);
      }
      requestBody.writeProducts(products);
      requestBody.endProducts();
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("changed-products", e);
    }
  }

  public void writeEndpoints() {
    if (!isWithinSizeLimits() || !eventSource.hasEndpoint()) {
      return;
    }
    try {
      log.debug("Writing endpoints");
      requestBody.beginEndpoints();
      boolean first = false;
      int remaining = Config.get().getApiSecurityEndpointCollectionMessageLimit();
      while (eventSource.hasEndpoint() && remaining > 0) {
        final Endpoint event = eventSource.nextEndpoint();
        remaining--;
        if (event.isFirst()) {
          first = true;
        }
        requestBody.writeEndpoint(event);
        eventSink.addEndpointEvent(event);
        if (!isWithinSizeLimits()) {
          break;
        }
      }
      requestBody.endEndpoints(first);
    } catch (IOException e) {
      throw new TelemetryRequestBody.SerializationException("asm-endpoints", e);
    }
  }

  public void writeHeartbeat() {
    requestBody.writeHeartbeatEvent();
  }

  private boolean isWithinSizeLimits() {
    return requestBody.size() < messageBytesSoftLimit;
  }
}
