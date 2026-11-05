package datadog.trace.lambda;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exchanges trace identity with the extension without transferring Lambda event payloads. */
public final class LambdaNativeClient {
  static final String PATH = "/lambda/trace-context";
  private static final Logger log = LoggerFactory.getLogger(LambdaNativeClient.class);
  private static final MediaType JSON = MediaType.parse("application/json");
  private static final JsonAdapter<Object> ADAPTER =
      new Moshi.Builder().build().adapter(Object.class);
  private final OkHttpClient client;
  private final String url;
  private volatile Boolean supported;

  public LambdaNativeClient() {
    this(
        "http://127.0.0.1:8124",
        new OkHttpClient.Builder()
            .connectTimeout(200, MILLISECONDS)
            .readTimeout(200, MILLISECONDS)
            .writeTimeout(200, MILLISECONDS)
            .callTimeout(200, MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build());
  }

  LambdaNativeClient(String baseUrl, OkHttpClient client) {
    this.url = baseUrl.replaceAll("/+$", "") + PATH;
    this.client = client;
  }

  private Request.Builder request() {
    return new Request.Builder().url(url).header("Datadog-Meta-Lang", "java");
  }

  private synchronized boolean supported() {
    if (supported == null) {
      supported = false;
      try (Response response = client.newCall(request().get().build()).execute()) {
        if (response.isSuccessful() && response.body() != null) {
          Object value = ADAPTER.fromJson(response.body().source());
          if (value instanceof Map) {
            Map<?, ?> capabilities = (Map<?, ?>) value;
            supported =
                Double.valueOf(1).equals(capabilities.get("version"))
                    && Boolean.TRUE.equals(capabilities.get("native_invocation"));
          }
        }
      } catch (IOException | RuntimeException failure) {
        log.debug("Native Lambda capability discovery failed", failure);
      }
      if (!supported) {
        log.warn(
            "The extension does not support native Lambda trace correlation; upgrade the extension");
      }
    }
    return supported;
  }

  public Invocation begin(AgentSpan span, long parentId, String requestId) {
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("version", 1);
    identity.put("phase", "begin");
    identity.put("request_id", requestId);
    identity.put("trace_id", span.getTraceId().toHexString());
    identity.put("span_id", DDSpanId.toHexStringPadded(span.getSpanId()));
    identity.put("parent_id", DDSpanId.toHexStringPadded(parentId));
    identity.put("start_ns", span.getStartTime());
    identity.put("service", span.getServiceName());
    identity.put("resource", span.getResourceName().toString());
    identity.put("sampling_priority", span.forceSamplingDecision());
    Map<?, ?> acknowledgment = supported() ? send(identity) : null;
    Boolean consumed =
        acknowledgment != null
                && Double.valueOf(1).equals(acknowledgment.get("version"))
                && acknowledgment.get("dsm_consumed") instanceof Boolean
            ? (Boolean) acknowledgment.get("dsm_consumed")
            : null;
    return new Invocation(identity, acknowledgment != null, consumed);
  }

  private Map<?, ?> send(Map<String, Object> metadata) {
    try (Response response =
        client
            .newCall(request().post(RequestBody.create(JSON, ADAPTER.toJson(metadata))).build())
            .execute()) {
      if (!response.isSuccessful()) {
        return null;
      }
      if (response.body() != null) {
        try {
          Object acknowledgment = ADAPTER.fromJson(response.body().source());
          if (acknowledgment instanceof Map) {
            return (Map<?, ?>) acknowledgment;
          }
        } catch (IOException | RuntimeException ignored) {
          // Identity can be accepted by an extension without DSM ownership support.
        }
      }
      return emptyMap();
    } catch (IOException | RuntimeException failure) {
      log.debug("Native Lambda identity delivery failed", failure);
      return null;
    }
  }

  public final class Invocation {
    private final Map<String, Object> identity;
    private final boolean accepted;
    private final Boolean dsmConsumed;
    private boolean completed;

    private Invocation(Map<String, Object> identity, boolean accepted, Boolean dsmConsumed) {
      this.identity = identity;
      this.accepted = accepted;
      this.dsmConsumed = dsmConsumed;
    }

    public boolean isAccepted() {
      return accepted;
    }

    public boolean hasDsmOwnership() {
      return accepted && dsmConsumed != null;
    }

    public boolean shouldRecordDsm() {
      return accepted && Boolean.FALSE.equals(dsmConsumed);
    }

    public synchronized void complete(AgentSpan span) {
      if (!accepted || completed) {
        return;
      }
      completed = true;
      Map<String, Object> metadata = new LinkedHashMap<>(identity);
      metadata.put("phase", "complete");
      metadata.put("duration_ns", span.getDurationNano());
      metadata.put("error", span.isError());
      metadata.put("sampling_priority", span.forceSamplingDecision());
      send(metadata);
    }
  }
}
