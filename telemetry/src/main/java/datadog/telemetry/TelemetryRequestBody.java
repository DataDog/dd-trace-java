package datadog.telemetry;

import com.squareup.moshi.JsonWriter;
import datadog.communication.ddagent.TracerVersion;
import datadog.environment.JavaVirtualMachine;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.DDTags;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.ProductChange.ProductType;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;

public class TelemetryRequestBody extends RequestBody {

  public static class SerializationException extends RuntimeException {
    public SerializationException(String requestPartName, Throwable cause) {
      super("Failed serializing Telemetry " + requestPartName + " part!", cause);
    }
  }

  private static final AtomicLong SEQ_ID = new AtomicLong();
  private static final String TELEMETRY_NAMESPACE_TAG_TRACER = "tracers";

  private final RequestType requestType;
  private final Buffer body;
  private final JsonWriter bodyWriter;

  /** Exists in a separate class to avoid startup toll */
  private static class CommonData {
    final Config config = Config.get();
    final String env = config.getEnv();
    final String langVersion = JavaVirtualMachine.getLangVersion();
    final String runtimeName = JavaVirtualMachine.getRuntimeVendor();
    final String runtimePatches = JavaVirtualMachine.getRuntimePatches();
    final String runtimeVersion = JavaVirtualMachine.getRuntimeVersion();
    final String serviceName = config.getServiceName();
    final String serviceVersion = config.getVersion();
    final String runtimeId = config.getRuntimeId();
    final String architecture = HostInfo.getArchitecture();
    final String hostname = HostInfo.getHostname();
    final String kernelName = HostInfo.getKernelName();
    final String kernelRelease = HostInfo.getKernelRelease();
    final String kernelVersion = HostInfo.getKernelVersion();
    final String osName = HostInfo.getOsName();
    final String osVersion = HostInfo.getOsVersion();
  }

  private static final CommonData commonData = new CommonData();

  TelemetryRequestBody(RequestType requestType) {
    this.requestType = requestType;
    this.body = new Buffer();
    this.bodyWriter = JsonWriter.of(body);
  }

  public void beginRequest(boolean debug) {
    try {
      bodyWriter.beginObject();
      bodyWriter.name("api_version").value(TelemetryRequest.API_VERSION);
      // naming_schema_version - optional
      bodyWriter.name("runtime_id").value(commonData.runtimeId);
      bodyWriter.name("seq_id").value(SEQ_ID.incrementAndGet());
      bodyWriter.name("tracer_time").value(System.currentTimeMillis() / 1000L);

      bodyWriter.name("application");
      bodyWriter.beginObject();
      bodyWriter.name("service_name").value(commonData.serviceName);
      bodyWriter.name("env").value(commonData.env);
      bodyWriter.name("service_version").value(commonData.serviceVersion);
      bodyWriter.name("tracer_version").value(TracerVersion.TRACER_VERSION);
      bodyWriter.name("language_name").value(DDTags.LANGUAGE_TAG_VALUE);
      bodyWriter.name("language_version").value(commonData.langVersion);
      bodyWriter.name("runtime_name").value(commonData.runtimeName);
      bodyWriter.name("runtime_version").value(commonData.runtimeVersion);
      bodyWriter.name("runtime_patches").value(commonData.runtimePatches); // optional
      final CharSequence processTags = ProcessTags.getTagsForSerialization();
      if (processTags != null) {
        bodyWriter.name("process_tags").value(processTags.toString());
      }
      bodyWriter.endObject();

      if (debug) {
        bodyWriter.name("debug").value(true);
      }
      bodyWriter.name("host");
      bodyWriter.beginObject();
      bodyWriter.name("hostname").value(commonData.hostname);
      bodyWriter.name("os").value(commonData.osName);
      bodyWriter.name("os_version").value(commonData.osVersion); // optional
      bodyWriter.name("architecture").value(commonData.architecture);
      // only applicable to UNIX based OS
      bodyWriter.name("kernel_name").value(commonData.kernelName);
      bodyWriter.name("kernel_release").value(commonData.kernelRelease);
      bodyWriter.name("kernel_version").value(commonData.kernelVersion);
      bodyWriter.endObject();

      bodyWriter.name("request_type").value(this.requestType.toString());

      switch (this.requestType) {
        case APP_STARTED:
        case APP_EXTENDED_HEARTBEAT:
          bodyWriter.name("payload");
          bodyWriter.beginObject();
          break;
        case MESSAGE_BATCH:
          bodyWriter.name("payload");
          bodyWriter.beginArray();
          break;
        default:
      }
    } catch (Exception ex) {
      throw new SerializationException("begin-request", ex);
    }
  }

  /** @return body size in bytes */
  public long endRequest() {
    try {
      switch (this.requestType) {
        case APP_STARTED:
        case APP_EXTENDED_HEARTBEAT:
          bodyWriter.endObject(); // payload
          break;
        case MESSAGE_BATCH:
          bodyWriter.endArray(); // payloads
          break;
        default:
      }
      bodyWriter.endObject(); // request
      return body.size();
    } catch (Exception ex) {
      throw new SerializationException("end-request", ex);
    }
  }

  public void writeHeartbeatEvent() {
    beginMessageIfBatch(RequestType.APP_HEARTBEAT);
    endMessageIfBatch(RequestType.APP_HEARTBEAT);
  }

  public void beginMetrics() throws IOException {
    beginMessageIfBatch(RequestType.GENERATE_METRICS);
    bodyWriter.name("namespace").value(TELEMETRY_NAMESPACE_TAG_TRACER);
    bodyWriter.name("series").beginArray();
  }

  public void writeMetric(Metric m) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("metric").value(m.getMetric());
    bodyWriter.name("points").jsonValue(m.getPoints());
    // interval - optional
    if (m.getType() != null) bodyWriter.name("type").value(m.getType().toString());
    bodyWriter.name("tags").jsonValue(m.getTags()); // optional
    bodyWriter.name("common").value(m.getCommon()); // optional
    bodyWriter.name("namespace").value(m.getNamespace()); // optional
    bodyWriter.endObject();
  }

  public void endMetrics() throws IOException {
    bodyWriter.endArray();
    endMessageIfBatch(RequestType.GENERATE_METRICS);
  }

  public void beginDistributions() throws IOException {
    beginMessageIfBatch(RequestType.DISTRIBUTIONS);
    bodyWriter.name("namespace").value(TELEMETRY_NAMESPACE_TAG_TRACER);
    bodyWriter.name("series").beginArray();
  }

  public void writeDistribution(DistributionSeries ds) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("metric").value(ds.getMetric());
    bodyWriter.name("points").jsonValue(ds.getPoints());
    bodyWriter.name("tags").jsonValue(ds.getTags());
    bodyWriter.name("common").value(ds.getCommon());
    bodyWriter.name("namespace").value(ds.getNamespace());
    bodyWriter.endObject();
  }

  public void endDistributions() throws IOException {
    bodyWriter.endArray();
    endMessageIfBatch(RequestType.DISTRIBUTIONS);
  }

  public void beginLogs() throws IOException {
    beginMessageIfBatch(RequestType.LOGS);
    bodyWriter.name("logs").beginArray();
  }

  public void writeLog(LogMessage m) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("message").value(m.getMessage());
    bodyWriter.name("level").value(String.valueOf(m.getLevel()));
    bodyWriter.name("tags").value(m.getTags()); // optional
    bodyWriter.name("stack_trace").value(m.getStackTrace()); // optional
    bodyWriter.name("tracer_time").value(m.getTracerTime()); // optional
    bodyWriter.name("count").value(m.getCount()); // optional
    bodyWriter.endObject();
  }

  public void endLogs() throws IOException {
    bodyWriter.endArray();
    endMessageIfBatch(RequestType.LOGS);
  }

  public void beginConfiguration() throws IOException {
    beginMessageIfBatch(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
    bodyWriter.name("configuration").beginArray();
  }

  public void writeConfiguration(ConfigSetting configSetting) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("name").value(configSetting.normalizedKey());
    bodyWriter.setSerializeNulls(true);
    bodyWriter.name("value").value(configSetting.stringValue());
    bodyWriter.setSerializeNulls(false);
    bodyWriter.name("origin").value(configSetting.origin.value);
    bodyWriter.name("seq_id").value(configSetting.seqId);
    if (configSetting.configId != null) {
      bodyWriter.name("config_id").value(configSetting.configId);
    }
    bodyWriter.endObject();
  }

  public void endConfiguration() throws IOException {
    bodyWriter.endArray();
    endMessageIfBatch(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
  }

  public void beginIntegrations() throws IOException {
    beginMessageIfBatch(RequestType.APP_INTEGRATIONS_CHANGE);
    bodyWriter.name("integrations").beginArray();
  }

  public void writeIntegration(Integration i) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("enabled").value(i.enabled);
    bodyWriter.name("name").value(i.name);
    bodyWriter.endObject();
  }

  public void endIntegrations() throws IOException {
    bodyWriter.endArray();
    endMessageIfBatch(RequestType.APP_INTEGRATIONS_CHANGE);
  }

  public void beginDependencies() throws IOException {
    beginMessageIfBatch(RequestType.APP_DEPENDENCIES_LOADED);
    bodyWriter.name("dependencies").beginArray();
  }

  public void writeDependency(Dependency d) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("hash").value(d.hash); // optional
    bodyWriter.name("name").value(d.name);
    bodyWriter.name("version").value(d.version); // optional
    bodyWriter.endObject();
  }

  public void endDependencies() throws IOException {
    bodyWriter.endArray();
    endMessageIfBatch(RequestType.APP_DEPENDENCIES_LOADED);
  }

  public void beginProducts() throws IOException {
    beginMessageIfBatch(RequestType.APP_PRODUCT_CHANGE);
  }

  public void writeProducts(final Map<ProductType, Boolean> products) throws IOException {

    if (products == null || products.isEmpty()) {
      return;
    }
    bodyWriter.name("products");
    bodyWriter.beginObject();

    for (Map.Entry<ProductType, Boolean> entry : products.entrySet()) {
      bodyWriter.name(entry.getKey().getName());
      bodyWriter.beginObject();
      bodyWriter.name("enabled").value(entry.getValue());
      bodyWriter.endObject();
    }

    bodyWriter.endObject();
  }

  public void writeProducts(
      boolean appsecEnabled, boolean profilerEnabled, boolean dynamicInstrumentationEnabled)
      throws IOException {
    Map<ProductType, Boolean> products = new EnumMap<>(ProductType.class);
    products.put(ProductType.APPSEC, appsecEnabled);
    products.put(ProductType.PROFILER, profilerEnabled);
    products.put(ProductType.DYNAMIC_INSTRUMENTATION, dynamicInstrumentationEnabled);
    writeProducts(products);
  }

  public void endProducts() throws IOException {
    endMessageIfBatch(RequestType.APP_PRODUCT_CHANGE);
  }

  public void beginEndpoints() throws IOException {
    beginMessageIfBatch(RequestType.APP_ENDPOINTS);
    bodyWriter.name("endpoints");
    bodyWriter.beginArray();
  }

  public void writeEndpoint(final Endpoint endpoint) throws IOException {
    bodyWriter.beginObject();
    if (endpoint.getType() != null) {
      bodyWriter.name("type").value(endpoint.getType());
    }
    if (endpoint.getMethod() != null) {
      bodyWriter.name("method").value(endpoint.getMethod());
    }
    if (endpoint.getPath() != null) {
      bodyWriter.name("path").value(endpoint.getPath());
    }
    bodyWriter.name("operation_name").value(endpoint.getOperation());
    bodyWriter.name("resource_name").value(endpoint.getResource());
    if (endpoint.getRequestBodyType() != null) {
      bodyWriter.name("request_body_type").jsonValue(endpoint.getRequestBodyType());
    }
    if (endpoint.getResponseBodyType() != null) {
      bodyWriter.name("response_body_type").jsonValue(endpoint.getResponseBodyType());
    }
    if (endpoint.getResponseCode() != null) {
      bodyWriter.name("response_code").jsonValue(endpoint.getResponseCode());
    }
    if (endpoint.getAuthentication() != null) {
      bodyWriter.name("authentication").jsonValue(endpoint.getAuthentication());
    }
    if (endpoint.getMetadata() != null) {
      bodyWriter.name("metadata").jsonValue(endpoint.getMetadata());
    }
    bodyWriter.endObject();
  }

  public void endEndpoints(final boolean first) throws IOException {
    bodyWriter.endArray();
    bodyWriter.name("is_first").value(first);
    endMessageIfBatch(RequestType.APP_ENDPOINTS);
  }

  public void writeInstallSignature(String installId, String installType, String installTime)
      throws IOException {
    if (installId == null && installType == null && installTime == null) {
      return;
    }
    bodyWriter.name("install_signature");
    bodyWriter.beginObject();
    bodyWriter.name("install_id").value(installId);
    bodyWriter.name("install_type").value(installType);
    bodyWriter.name("install_time").value(installTime);
    bodyWriter.endObject();
  }

  @Nullable
  @Override
  public MediaType contentType() {
    return TelemetryRequest.JSON;
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    sink.write(body, body.size());
  }

  public long size() {
    return body.size();
  }

  private void beginMessageIfBatch(RequestType messageType) {
    if (requestType != RequestType.MESSAGE_BATCH) {
      return;
    }
    try {
      bodyWriter.beginObject();
      bodyWriter.name("request_type").value(String.valueOf(messageType));
      if (messageType != RequestType.APP_HEARTBEAT) {
        bodyWriter.name("payload");
        bodyWriter.beginObject();
      }
    } catch (Exception ex) {
      throw new SerializationException("begin-message", ex);
    }
  }

  private void endMessageIfBatch(RequestType messageType) {
    if (requestType != RequestType.MESSAGE_BATCH) {
      return;
    }
    try {
      if (messageType != RequestType.APP_HEARTBEAT) {
        bodyWriter.endObject(); // payload
      }
      bodyWriter.endObject(); // message
    } catch (Exception ex) {
      throw new SerializationException("end-message", ex);
    }
  }
}
