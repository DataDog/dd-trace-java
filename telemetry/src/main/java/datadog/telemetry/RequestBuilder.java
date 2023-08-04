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
import java.util.List;
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
      super("Failed serializing Telemetry request " + requestPartName + " part!", cause);
    }
  }

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private static final String API_VERSION = "v2";
  private static final AtomicLong SEQ_ID = new AtomicLong();
  private static final String TELEMETRY_NAMESPACE_TAG_TRACER = "tracers";

  private final Buffer body = new Buffer();
  private final JsonWriter bodyWriter = JsonWriter.of(body);
  private final RequestType requestType;
  private final Request request;
  private final boolean debug;

  private enum CommonData {
    INSTANCE;

    Config config = Config.get();
    String env = config.getEnv();
    String langVersion = Platform.getLangVersion();
    String runtimeName = Platform.getRuntimeVendor();
    String runtimePatches = Platform.getRuntimePatches();
    String runtimeVersion = Platform.getRuntimeVersion();
    String serviceName = config.getServiceName();
    String serviceVersion = config.getVersion();
    String runtimeId = config.getRuntimeId();
    String architecture = HostInfo.getArchitecture();
    String hostname = HostInfo.getHostname();
    String kernelName = HostInfo.getKernelName();
    String kernelRelease = HostInfo.getKernelRelease();
    String kernelVersion = HostInfo.getKernelVersion();
    String osName = HostInfo.getOsName();
    String osVersion = HostInfo.getOsVersion();
  }

  RequestBuilder(RequestType requestType, HttpUrl httpUrl) {
    this(requestType, httpUrl, false);
  }

  RequestBuilder(RequestType requestType, HttpUrl httpUrl, boolean debug) {
    this.requestType = requestType;
    this.request =
        new Request.Builder()
            .url(httpUrl)
            .addHeader("Content-Type", String.valueOf(JSON))
            .addHeader("DD-Telemetry-API-Version", API_VERSION)
            .addHeader("DD-Telemetry-Request-Type", String.valueOf(requestType))
            .addHeader("DD-Client-Library-Language", "jvm")
            .addHeader("DD-Client-Library-Version", TracerVersion.TRACER_VERSION)
            .post(this)
            .build();
    this.debug = debug;
  }

  public void beginRequest() {
    try {
      CommonData commonData = CommonData.INSTANCE;
      bodyWriter.beginObject();
      bodyWriter.name("api_version").value(API_VERSION);
      // naming_schema_version - optional
      bodyWriter.name("request_type").value(requestType.toString());
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

      bodyWriter.name("request_type").value(requestType.toString());
      if (requestType == RequestType.MESSAGE_BATCH) {
        bodyWriter.name("payload");
        bodyWriter.beginArray();
      }
    } catch (Exception ex) {
      throw new SerializationException("header", ex);
    }
  }

  public void endRequest() {
    try {
      if (requestType == RequestType.MESSAGE_BATCH) {
        bodyWriter.endArray(); // payload
      }
      bodyWriter.endObject(); // request
    } catch (Exception ex) {
      throw new SerializationException("footer", ex);
    }
  }

  private void beginMessage(RequestType payloadType) {
    try {
      if (requestType == RequestType.MESSAGE_BATCH) {
        bodyWriter.beginObject();
        bodyWriter.name("request_type").value(String.valueOf(payloadType));
      }
    } catch (Exception ex) {
      throw new SerializationException("begin-batch", ex);
    }
  }

  private void endMessage() {
    try {
      if (requestType == RequestType.MESSAGE_BATCH) {
        bodyWriter.endObject(); // event
      }
    } catch (Exception ex) {
      throw new SerializationException("end-batch", ex);
    }
  }

  private void beginPayload() {
    try {
      bodyWriter.name("payload");
      bodyWriter.beginObject();
    } catch (Exception ex) {
      throw new SerializationException("begin-batch", ex);
    }
  }

  private void endPayload() {
    try {
      bodyWriter.endObject(); // payload
    } catch (Exception ex) {
      throw new SerializationException("end-batch", ex);
    }
  }

  public void writeHeartbeatEvent() {
    beginMessage(RequestType.APP_HEARTBEAT);
    endMessage();
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/metric_data.md#metric_data
   */
  public void writeGenerateMetricsEvent(List<Metric> series) {
    if (series.isEmpty()) {
      return;
    }
    try {
      beginMessage(RequestType.GENERATE_METRICS);
      beginPayload();
      bodyWriter.name("namespace").value(TELEMETRY_NAMESPACE_TAG_TRACER);
      bodyWriter.name("series");
      bodyWriter.beginArray();
      for (Metric m : series) {
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
      bodyWriter.endArray();
      endPayload();
      endMessage();
    } catch (Exception ex) {
      throw new SerializationException("metrics payload", ex);
    }
  }

  public void writeDistributionsEvent(List<DistributionSeries> series) {
    if (series.isEmpty()) {
      return;
    }
    try {
      beginMessage(RequestType.DISTRIBUTIONS);
      beginPayload();
      bodyWriter.name("namespace").value(TELEMETRY_NAMESPACE_TAG_TRACER);
      bodyWriter.name("series");
      bodyWriter.beginArray();
      for (DistributionSeries ds : series) {
        bodyWriter.beginObject();
        bodyWriter.name("metric").value(ds.getMetric());
        bodyWriter.name("points").jsonValue(ds.getPoints());
        bodyWriter.name("tags").jsonValue(ds.getTags());
        bodyWriter.name("common").value(ds.getCommon());
        bodyWriter.name("namespace").value(ds.getNamespace());
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
      endPayload();
      endMessage();
    } catch (Exception ex) {
      throw new SerializationException("distribution series payload", ex);
    }
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/payload.md#if-request_type--app-extended-heartbeat-we-add-the-following-to-payload
   */
  public void writeLogsEvent(List<LogMessage> messages) {
    if (messages.isEmpty()) {
      return;
    }
    try {
      beginMessage(RequestType.LOGS);
      beginPayload();
      bodyWriter.name("logs").beginArray();
      for (LogMessage m : messages) {
        bodyWriter.beginObject();
        bodyWriter.name("message").value(m.getMessage());
        bodyWriter.name("level").value(String.valueOf(m.getLevel()));
        bodyWriter.name("tags").value(m.getTags()); // optional
        bodyWriter.name("stack_trace").value(m.getStackTrace()); // optional
        bodyWriter.name("tracer_time").value(m.getTracerTime()); // optional
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
      endPayload();
      endMessage();
    } catch (Exception ex) {
      throw new SerializationException("logs payload", ex);
    }
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/app_started.md
   */
  public void writeAppStartedEvent(List<ConfigChange> configChanges) {
    if (configChanges != null) {
      beginMessage(RequestType.APP_STARTED);
      beginPayload();
      // products - optional
      writeConfigurationChanges(configChanges);
      // error - optional
      // additional_payload - optional
      endPayload();
      endMessage();
    }
  }

  public void writeConfigChangeEvent(List<ConfigChange> configChanges) {
    if (!configChanges.isEmpty()) {
      beginMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
      beginPayload();
      writeConfigurationChanges(configChanges);
      endPayload();
      endMessage();
    }
  }

  private void writeConfigurationChanges(List<ConfigChange> configChanges) {
    if (configChanges.isEmpty()) {
      return;
    }
    try {
      bodyWriter.name("configuration").beginArray();
      for (ConfigChange cc : configChanges) {
        bodyWriter.beginObject();
        bodyWriter.name("name").value(cc.name);
        bodyWriter.name("value").jsonValue(cc.value);
        // TODO provide a real origin when it's implemented
        bodyWriter.name("origin").jsonValue("unknown");
        // error - optional
        // seq_id - optional
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
    } catch (Exception ex) {
      throw new SerializationException("config changes payload", ex);
    }
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/dependency.md
   */
  public void writeDependenciesLoadedEvent(List<Dependency> dependencies) {
    if (dependencies.isEmpty()) {
      return;
    }
    try {
      beginMessage(RequestType.APP_DEPENDENCIES_LOADED);
      beginPayload();
      bodyWriter.name("dependencies");
      bodyWriter.beginArray();
      for (Dependency d : dependencies) {
        bodyWriter.beginObject();
        bodyWriter.name("hash").value(d.hash); // optional
        bodyWriter.name("name").value(d.name);
        bodyWriter.name("version").value(d.version); // optional
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
      endPayload();
      endMessage();
    } catch (Exception ex) {
      throw new SerializationException("dependencies payload", ex);
    }
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/integration.md
   */
  public void writeIntegrationsEvent(List<Integration> integrations) {
    if (integrations.isEmpty()) {
      return;
    }
    try {
      beginMessage(RequestType.APP_INTEGRATIONS_CHANGE);
      beginPayload();
      bodyWriter.name("integrations");
      bodyWriter.beginArray();
      for (Integration i : integrations) {
        bodyWriter.beginObject();
        // auto_enabled - optional
        // compatible - optional
        bodyWriter.name("enabled").value(i.enabled);
        // error - optional
        bodyWriter.name("name").value(i.name);
        // version - optional
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
      endPayload();
      endMessage();
    } catch (Exception ex) {
      throw new SerializationException("integrations payload", ex);
    }
  }

  /**
   * https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/products.md
   */
  public void writeProductChangeEvent() {
    // TODO
    // appsec - optional
    // profiler - optional
    // dynamic_instrumentation - optional
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

  /**
   * @return associated request. <br>
   *     NOTE: Its body can still be extended with write methods until it's been sent. That's
   *     because the request body is lazily evaluated and backed with a buffer that can still be
   *     extended until the request is sent. <br>
   *     TODO: see if there is a better way to express this peculiarity in the code.
   */
  public Request request() {
    return request;
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
}
