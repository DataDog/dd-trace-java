package datadog.telemetry;

import com.squareup.moshi.JsonWriter;
import datadog.communication.ddagent.TracerVersion;
import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Platform;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;

public class RequestBuilder extends RequestBody {

  public static class SerializationException extends RuntimeException {

    public SerializationException(String requestPartName, Throwable cause) {
      super("Failed serializing Telemetry " + requestPartName + " part!", cause);
    }
  }

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private static final String API_VERSION = "v2";
  private static final AtomicLong SEQ_ID = new AtomicLong();
  private static final String TELEMETRY_NAMESPACE_TAG_TRACER = "tracers";
  private final Buffer body = new Buffer();

  private final JsonWriter bodyWriter = JsonWriter.of(body);
  private final RequestType requestType;
  private final Request.Builder requestBuilder;
  private final boolean debug;

  private enum CommonData {
    INSTANCE;

    final Config config = Config.get();
    final String env = config.getEnv();
    final String langVersion = Platform.getLangVersion();
    final String runtimeName = Platform.getRuntimeVendor();
    final String runtimePatches = Platform.getRuntimePatches();
    final String runtimeVersion = Platform.getRuntimeVersion();
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

  RequestBuilder(RequestType requestType, HttpUrl httpUrl) {
    this(requestType, httpUrl, false);
  }

  RequestBuilder(RequestType requestType, HttpUrl httpUrl, boolean debug) {
    this.requestType = requestType;
    this.requestBuilder =
        new Request.Builder()
            .url(httpUrl)
            .addHeader("Content-Type", String.valueOf(JSON))
            .addHeader("DD-Telemetry-API-Version", API_VERSION)
            .addHeader("DD-Telemetry-Request-Type", String.valueOf(this.requestType))
            .addHeader("DD-Client-Library-Language", "jvm")
            .addHeader("DD-Client-Library-Version", TracerVersion.TRACER_VERSION)
            .post(this);
    this.debug = debug;
  }

  public void beginRequest() {
    try {
      CommonData commonData = CommonData.INSTANCE;
      bodyWriter.beginObject();
      bodyWriter.name("api_version").value(API_VERSION);
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

      if (this.requestType == RequestType.APP_STARTED) {
        beginSinglePayload();
      } else if (this.requestType == RequestType.MESSAGE_BATCH) {
        beginMultiplePayload();
      }
    } catch (Exception ex) {
      throw new SerializationException("begin-request", ex);
    }
  }

  public Request endRequest() {
    try {
      if (requestType == RequestType.APP_STARTED) {
        endSinglePayload();
      } else if (requestType == RequestType.MESSAGE_BATCH) {
        endMultiplePayload();
      }
      bodyWriter.endObject(); // request
      requestBuilder.addHeader("Content-Length", String.valueOf(body.size()));
      return buildRequest();
    } catch (Exception ex) {
      throw new SerializationException("end-request", ex);
    }
  }

  public void beginMessage(RequestType payloadType) {
    if (requestType != RequestType.MESSAGE_BATCH) {
      throw new IllegalStateException(
          "Serializing begin-message is only allowed within a message-batch request");
    }
    try {
      bodyWriter.beginObject();
      bodyWriter.name("request_type").value(String.valueOf(payloadType));
    } catch (Exception ex) {
      throw new SerializationException("begin-message", ex);
    }
  }

  public void endMessage() {
    if (requestType != RequestType.MESSAGE_BATCH) {
      throw new IllegalStateException(
          "Serializing end-message is only allowed within a message-batch request");
    }
    try {
      bodyWriter.endObject(); // message
    } catch (Exception ex) {
      throw new SerializationException("end-message", ex);
    }
  }

  public void beginSinglePayload() {
    try {
      bodyWriter.name("payload");
      bodyWriter.beginObject();
    } catch (Exception ex) {
      throw new SerializationException("begin-single-payload", ex);
    }
  }

  public void endSinglePayload() {
    try {
      bodyWriter.endObject();
    } catch (Exception ex) {
      throw new SerializationException("end-single-payload", ex);
    }
  }

  public void beginMultiplePayload() {
    try {
      bodyWriter.name("payload");
      bodyWriter.beginArray();
    } catch (Exception ex) {
      throw new SerializationException("begin-multiple-payload", ex);
    }
  }

  public void endMultiplePayload() {
    try {
      bodyWriter.endArray();
    } catch (Exception ex) {
      throw new SerializationException("end-multiple-payload", ex);
    }
  }

  public void writeHeartbeatEvent() {
    beginMessage(RequestType.APP_HEARTBEAT);
    endMessage();
  }

  public void beginMetrics() throws IOException {
    beginMessage(RequestType.GENERATE_METRICS);
    beginSinglePayload();
    bodyWriter.name("namespace").value(TELEMETRY_NAMESPACE_TAG_TRACER);
    bodyWriter.name("series");
    bodyWriter.beginArray();
  }

  public void endMetrics() throws IOException {
    bodyWriter.endArray();
    endSinglePayload();
    endMessage();
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/metric_data.md#metric_data
   */
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

  public void writeDistribution(DistributionSeries ds) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("metric").value(ds.getMetric());
    bodyWriter.name("points").jsonValue(ds.getPoints());
    bodyWriter.name("tags").jsonValue(ds.getTags());
    bodyWriter.name("common").value(ds.getCommon());
    bodyWriter.name("namespace").value(ds.getNamespace());
    bodyWriter.endObject();
  }

  public void beginDistributions() throws IOException {
    beginMessage(RequestType.DISTRIBUTIONS);
    beginSinglePayload();
    bodyWriter.name("namespace").value(TELEMETRY_NAMESPACE_TAG_TRACER);
    bodyWriter.name("series");
    bodyWriter.beginArray();
  }

  public void endDistributions() throws IOException {
    bodyWriter.endArray();
    endSinglePayload();
    endMessage();
  }

  public void beginLogs() throws IOException {
    beginMessage(RequestType.LOGS);
    beginSinglePayload();
    bodyWriter.name("logs").beginArray();
  }

  public void endLogs() throws IOException {
    bodyWriter.endArray();
    endSinglePayload();
    endMessage();
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/payload.md#if-request_type--app-extended-heartbeat-we-add-the-following-to-payload
   */
  public void writeLog(LogMessage m) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("message").value(m.getMessage());
    bodyWriter.name("level").value(String.valueOf(m.getLevel()));
    bodyWriter.name("tags").value(m.getTags()); // optional
    bodyWriter.name("stack_trace").value(m.getStackTrace()); // optional
    bodyWriter.name("tracer_time").value(m.getTracerTime()); // optional
    bodyWriter.endObject();
  }

  public void beginConfiguration() throws IOException {
    bodyWriter.name("configuration").beginArray();
  }

  public void endConfiguration() throws IOException {
    bodyWriter.endArray();
  }

  public void writeConfiguration(ConfigChange cc) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("name").value(cc.name);
    bodyWriter.name("value").jsonValue(cc.value);
    // TODO provide a real origin when it's implemented
    bodyWriter.name("origin").jsonValue("unknown");
    // error - optional
    // seq_id - optional
    bodyWriter.endObject();
  }

  public void beginIntegrations() throws IOException {
    beginMessage(RequestType.APP_INTEGRATIONS_CHANGE);
    beginSinglePayload();
    bodyWriter.name("integrations");
    bodyWriter.beginArray();
  }

  public void endIntegrations() throws IOException {
    bodyWriter.endArray();
    endSinglePayload();
    endMessage();
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/integration.md
   */
  public void writeIntegration(Integration i) throws IOException {
    bodyWriter.beginObject();
    // auto_enabled - optional
    // compatible - optional
    bodyWriter.name("enabled").value(i.enabled);
    // error - optional
    bodyWriter.name("name").value(i.name);
    // version - optional
    bodyWriter.endObject();
  }

  public void beginDependencies() throws IOException {
    beginMessage(RequestType.APP_DEPENDENCIES_LOADED);
    beginSinglePayload();
    bodyWriter.name("dependencies");
    bodyWriter.beginArray();
  }

  public void endDependencies() throws IOException {
    bodyWriter.endArray();
    endSinglePayload();
    endMessage();
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/dependency.md
   */
  public void writeDependency(Dependency d) throws IOException {
    bodyWriter.beginObject();
    bodyWriter.name("hash").value(d.hash); // optional
    bodyWriter.name("name").value(d.name);
    bodyWriter.name("version").value(d.version); // optional
    bodyWriter.endObject();
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/products.md
   */
  public void writeProducts(boolean appsecEnabled, boolean profilerEnabled) throws IOException {
    bodyWriter.name("products");
    bodyWriter.beginObject();

    bodyWriter.name("appsec");
    bodyWriter.beginObject();
    bodyWriter.name("enabled").value(appsecEnabled);
    bodyWriter.endObject();

    bodyWriter.name("profiler");
    bodyWriter.beginObject();
    bodyWriter.name("enabled").value(profilerEnabled);
    bodyWriter.endObject();

    bodyWriter.endObject();
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/payload.md#if-request_type--app-extended-heartbeat-we-add-the-following-to-payload
   */
  public void extendedHeartbeat() {
    // TODO
    // configuration - optional
    // dependencies - optional
    // integrations - optional
  }

  Request buildRequest() {
    return requestBuilder.build();
  }

  @Nullable
  @Override
  public MediaType contentType() {
    return JSON;
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    sink.write(body, body.size());
  }

  public long size() {
    return body.size();
  }
}
