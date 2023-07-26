package datadog.telemetry;

import com.squareup.moshi.JsonWriter;
import datadog.communication.ddagent.TracerVersion;
import datadog.telemetry.api.Dependency;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.KeyValue;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
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
  private static final String API_VERSION = "v1";
  private static final AtomicLong SEQ_ID = new AtomicLong();
  private static final String TELEMETRY_NAMESPACE_TAG_TRACER = "tracers";

  private final Buffer body = new Buffer();
  private final JsonWriter bodyWriter = JsonWriter.of(body);
  private final RequestBuilderProvider provider;
  private final RequestType requestType;
  private final Request request;

  RequestBuilder(RequestBuilderProvider provider, RequestType requestType) {
    this.provider = provider;
    this.requestType = requestType;
    this.request =
        new Request.Builder()
            .url(provider.httpUrl)
            .addHeader("Content-Type", String.valueOf(JSON))
            .addHeader("DD-Telemetry-API-Version", API_VERSION)
            .addHeader("DD-Telemetry-Request-Type", String.valueOf(requestType))
            .addHeader("DD-Client-Library-Language", "jvm")
            .addHeader("DD-Client-Library-Version", TracerVersion.TRACER_VERSION)
            .post(this)
            .build();
  }

  public void writeHeader() {
    try {
      bodyWriter.beginObject();
      bodyWriter.name("api_version").value(API_VERSION);

      bodyWriter.name("application");
      bodyWriter.beginObject();
      bodyWriter.name("env").value(provider.env);
      bodyWriter.name("language_name").value(provider.languageName);
      bodyWriter.name("language_version").value(provider.languageVersion);
      bodyWriter.name("runtime_name").value(provider.runtimeName);
      bodyWriter.name("runtime_patches").value(provider.runtimePatches); // optional
      bodyWriter.name("runtime_version").value(provider.runtimeVersion);
      bodyWriter.name("service_name").value(provider.serviceName);
      bodyWriter.name("service_version").value(provider.serviceVersion);
      bodyWriter.name("tracer_version").value(provider.tracerVersion);
      bodyWriter.endObject();

      bodyWriter.name("debug").value(provider.debug);

      bodyWriter.name("host");
      bodyWriter.beginObject();
      bodyWriter.name("architecture").value(provider.architecture);
      bodyWriter.name("hostname").value(provider.hostname);
      // only applicable to UNIX based OS
      bodyWriter.name("kernel_name").value(provider.kernelName);
      bodyWriter.name("kernel_release").value(provider.kernelRelease);
      bodyWriter.name("kernel_version").value(provider.kernelVersion);
      bodyWriter.name("os").value(provider.os);
      bodyWriter.name("os_version").value(provider.osVersion); // optional
      bodyWriter.endObject();

      bodyWriter.name("request_type").value(requestType.toString());
      bodyWriter.name("runtime_id").value(provider.runtimeId);
      bodyWriter.name("seq_id").value(SEQ_ID.incrementAndGet());
      bodyWriter.name("tracer_time").value(System.currentTimeMillis() / 1000L);

      bodyWriter.name("payload");
      bodyWriter.beginObject();
    } catch (Exception ex) {
      throw new SerializationException("header", ex);
    }
  }

  public void writeMetrics(List<Metric> series) {
    try {
      bodyWriter.name("namespace").value(TELEMETRY_NAMESPACE_TAG_TRACER);
      bodyWriter.name("series");
      bodyWriter.beginArray();
      for (Metric m : series) {
        bodyWriter.beginObject();
        bodyWriter.name("namespace").value(m.getNamespace());
        bodyWriter.name("common").value(m.getCommon());
        bodyWriter.name("metric").value(m.getMetric());
        bodyWriter.name("points").jsonValue(m.getPoints());
        bodyWriter.name("tags").jsonValue(m.getTags());
        if (m.getType() != null) bodyWriter.name("type").value(m.getType().toString());
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
    } catch (Exception ex) {
      throw new SerializationException("metrics payload", ex);
    }
  }

  public void writeDistributionsEvent(List<DistributionSeries> series) {
    try {
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
    } catch (Exception ex) {
      throw new SerializationException("distribution series payload", ex);
    }
  }

  public void writeLogsEvent(List<LogMessage> messages) {
    try {
      bodyWriter.name("logs").beginArray();
      for (LogMessage m : messages) {
        bodyWriter.beginObject();
        bodyWriter.name("message").value(m.getMessage());
        bodyWriter.name("level").value(String.valueOf(m.getLevel()));
        bodyWriter.name("tags").value(m.getTags());
        bodyWriter.name("stack_trace").value(m.getStackTrace());
        bodyWriter.name("tracer_time").value(m.getTracerTime());
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
    } catch (Exception ex) {
      throw new SerializationException("logs payload", ex);
    }
  }

  public void writeConfigChangeEvent(List<KeyValue> kvs) {
    if (kvs != null) {
      try {
        bodyWriter.name("configuration").beginArray();
        for (KeyValue kv : kvs) {
          bodyWriter.beginObject();
          bodyWriter.name("name").value(kv.getName());
          bodyWriter.name("value").jsonValue(kv.getValue());
          bodyWriter.endObject();
        }
        bodyWriter.endArray();
      } catch (Exception ex) {
        throw new SerializationException("config changes payload", ex);
      }
    }
  }

  public void writeDependenciesLoadedEvent(List<Dependency> dependencies) {
    try {
      bodyWriter.name("dependencies");
      bodyWriter.beginArray();
      for (Dependency d : dependencies) {
        bodyWriter.beginObject();
        bodyWriter.name("hash").value(d.getHash());
        bodyWriter.name("name").value(d.getName());
        bodyWriter.name("type").value(String.valueOf(d.getType()));
        bodyWriter.name("version").value(d.getVersion());
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
    } catch (Exception ex) {
      throw new SerializationException("dependencies payload", ex);
    }
  }

  public void writeIntegrationsEvent(List<Integration> integrations) {
    try {
      bodyWriter.name("integrations");
      bodyWriter.beginArray();
      for (Integration i : integrations) {
        bodyWriter.beginObject();
        bodyWriter.name("auto_enabled").value(i.getAutoEnabled());
        bodyWriter.name("compatible").value(i.getCompatible());
        bodyWriter.name("enabled").value(i.getEnabled());
        bodyWriter.name("name").value(i.getName());
        bodyWriter.name("version").value(i.getVersion());
        bodyWriter.endObject();
      }
      bodyWriter.endArray();
    } catch (Exception ex) {
      throw new SerializationException("integrations payload", ex);
    }
  }

  public void writeFooter() {
    try {
      bodyWriter.endObject(); // payload
      bodyWriter.endObject(); // request
    } catch (Exception ex) {
      throw new SerializationException("footer", ex);
    }
  }

  /**
   * @return associated request. Its body can still be extended with write methods until it's sent.
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
