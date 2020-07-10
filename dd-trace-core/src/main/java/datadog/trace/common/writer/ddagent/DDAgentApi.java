package datadog.trace.common.writer.ddagent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.common.container.ContainerInfo;
import datadog.common.exec.CommonTaskExecutor;
import datadog.trace.common.writer.unixdomainsockets.UnixDomainSocketFactory;
import datadog.trace.core.DDTraceCoreInfo;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** The API pointing to a DD agent */
@Slf4j
public class DDAgentApi {
  private static final String DATADOG_META_LANG = "Datadog-Meta-Lang";
  private static final String DATADOG_META_LANG_VERSION = "Datadog-Meta-Lang-Version";
  private static final String DATADOG_META_LANG_INTERPRETER = "Datadog-Meta-Lang-Interpreter";
  private static final String DATADOG_META_LANG_INTERPRETER_VENDOR =
      "Datadog-Meta-Lang-Interpreter-Vendor";
  private static final String DATADOG_META_TRACER_VERSION = "Datadog-Meta-Tracer-Version";
  private static final String DATADOG_CONTAINER_ID = "Datadog-Container-ID";
  private static final String X_DATADOG_TRACE_COUNT = "X-Datadog-Trace-Count";

  private static final String TRACES_ENDPOINT_V3 = "v0.3/traces";
  private static final String TRACES_ENDPOINT_V4 = "v0.4/traces";
  private static final long NANOSECONDS_BETWEEN_ERROR_LOG = TimeUnit.MINUTES.toNanos(5);
  private static final String WILL_NOT_LOG_FOR_MESSAGE = "(Will not log errors for 5 minutes)";

  private final List<DDAgentResponseListener> responseListeners = new ArrayList<>();

  private long previousErrorLogNanos = System.nanoTime() - NANOSECONDS_BETWEEN_ERROR_LOG;
  private boolean logNextSuccess = false;
  private long totalTraces = 0;
  private long receivedTraces = 0;
  private long sentTraces = 0;
  private long failedTraces = 0;

  private static final JsonAdapter<Map<String, Map<String, Number>>> RESPONSE_ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(
              Types.newParameterizedType(
                  Map.class,
                  String.class,
                  Types.newParameterizedType(Map.class, String.class, Double.class)));
  private static final MediaType MSGPACK = MediaType.get("application/msgpack");

  private final String host;
  private final int port;
  private final String unixDomainSocketPath;
  private final long timeoutMillis;
  private OkHttpClient httpClient;
  private HttpUrl tracesUrl;

  public DDAgentApi(
      final String host,
      final int port,
      final String unixDomainSocketPath,
      final long timeoutMillis) {
    this.host = host;
    this.port = port;
    this.unixDomainSocketPath = unixDomainSocketPath;
    this.timeoutMillis = timeoutMillis;
  }

  public void addResponseListener(final DDAgentResponseListener listener) {
    if (!responseListeners.contains(listener)) {
      responseListeners.add(listener);
    }
  }

  Response sendSerializedTraces(
      final int representativeCount, final int traceCount, final ByteBuffer buffer) {
    if (httpClient == null) {
      detectEndpointAndBuildClient();
    }
    final int sizeInBytes = buffer.limit() - buffer.position();

    try {
      final Request request =
          prepareRequest(tracesUrl)
              .addHeader(X_DATADOG_TRACE_COUNT, Integer.toString(representativeCount))
              .put(new MsgPackRequestBody(buffer))
              .build();
      this.totalTraces += representativeCount;
      this.receivedTraces += traceCount;
      try (final okhttp3.Response response = httpClient.newCall(request).execute()) {
        if (response.code() != 200) {
          countAndLogFailedSend(traceCount, representativeCount, sizeInBytes, response, null);
          return Response.failed(response.code());
        }
        countAndLogSuccessfulSend(traceCount, representativeCount, sizeInBytes);
        String responseString = null;
        try {
          responseString = response.body().string().trim();
          if (!"".equals(responseString) && !"OK".equalsIgnoreCase(responseString)) {
            final Map<String, Map<String, Number>> parsedResponse =
                RESPONSE_ADAPTER.fromJson(responseString);
            final String endpoint = tracesUrl.toString();
            for (final DDAgentResponseListener listener : responseListeners) {
              listener.onResponse(endpoint, parsedResponse);
            }
          }
          return Response.success(response.code());
        } catch (final IOException e) {
          log.debug("Failed to parse DD agent response: {}", responseString, e);
          return Response.success(response.code(), e);
        }
      }
    } catch (final IOException e) {
      countAndLogFailedSend(traceCount, representativeCount, sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  private void countAndLogSuccessfulSend(
      final int traceCount, final int representativeCount, final int sizeInBytes) {
    // count the successful traces
    this.sentTraces += traceCount;

    if (log.isDebugEnabled()) {
      log.debug(createSendLogMessage(traceCount, representativeCount, sizeInBytes, "Success"));
    } else if (this.logNextSuccess) {
      this.logNextSuccess = false;
      if (log.isInfoEnabled()) {
        log.info(createSendLogMessage(traceCount, representativeCount, sizeInBytes, "Success"));
      }
    }
  }

  private void countAndLogFailedSend(
      final int traceCount,
      final int representativeCount,
      final int sizeInBytes,
      final okhttp3.Response response,
      final IOException outer) {
    // count the failed traces
    this.failedTraces += traceCount;

    // these are used to catch and log if there is a failure in debug logging the response body
    IOException exception = outer;
    boolean hasLogged = false;

    if (log.isDebugEnabled()) {
      String sendErrorString =
          createSendLogMessage(traceCount, representativeCount, sizeInBytes, "Error");
      if (response != null) {
        try {
          log.debug(
              "{} Status: {}, Response: {}, Body: {}",
              sendErrorString,
              response.code(),
              response.message(),
              response.body().string().trim());
          hasLogged = true;
        } catch (IOException inner) {
          exception = inner;
        }
      } else if (exception != null) {
        log.debug(sendErrorString, exception);
        hasLogged = true;
      } else {
        log.debug(sendErrorString);
        hasLogged = true;
      }
    }
    if (!hasLogged && log.isWarnEnabled()) {
      long now = System.nanoTime();
      if (now - this.previousErrorLogNanos >= NANOSECONDS_BETWEEN_ERROR_LOG) {
        this.previousErrorLogNanos = now;
        this.logNextSuccess = true;
        String sendErrorString =
            createSendLogMessage(traceCount, representativeCount, sizeInBytes, "Error");
        if (response != null) {
          log.warn(
              "{} Status: {} {} {}",
              sendErrorString,
              response.code(),
              response.message(),
              WILL_NOT_LOG_FOR_MESSAGE);
        } else if (exception != null) {
          log.warn(
              "{} {}: {} {}",
              sendErrorString,
              exception.getClass().getName(),
              exception.getMessage(),
              WILL_NOT_LOG_FOR_MESSAGE);
        } else {
          log.warn("{} {}", sendErrorString, WILL_NOT_LOG_FOR_MESSAGE);
        }
      }
    }
  }

  private String createSendLogMessage(
      final int traceCount,
      final int representativeCount,
      final int sizeInBytes,
      final String prefix) {
    int size = sizeInBytes;
    String sizeString = size > 1024 ? (size / 1024) + "KB" : size + "B";
    return prefix
        + " while sending "
        + traceCount
        + " of "
        + representativeCount
        + " (size="
        + sizeString
        + ")"
        + " traces to the DD agent."
        + " Total: "
        + this.totalTraces
        + ", Received: "
        + this.receivedTraces
        + ", Sent: "
        + this.sentTraces
        + ", Failed: "
        + this.failedTraces
        + ".";
  }

  // empty array - see messagepack spec
  private static final byte[] EMPTY_LIST = new byte[] {(byte) 0x90};

  private static boolean endpointAvailable(
      final HttpUrl url,
      final String unixDomainSocketPath,
      final long timeoutMillis,
      final boolean retry) {
    try {
      final OkHttpClient client = buildHttpClient(unixDomainSocketPath, timeoutMillis);
      final RequestBody body = RequestBody.create(MSGPACK, EMPTY_LIST);
      final Request request = prepareRequest(url).put(body).build();

      try (final okhttp3.Response response = client.newCall(request).execute()) {
        return response.code() == 200;
      }
    } catch (final IOException e) {
      if (retry) {
        return endpointAvailable(url, unixDomainSocketPath, timeoutMillis, false);
      }
    }
    return false;
  }

  private static OkHttpClient buildHttpClient(
      final String unixDomainSocketPath, final long timeoutMillis) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    if (unixDomainSocketPath != null) {
      builder = builder.socketFactory(new UnixDomainSocketFactory(new File(unixDomainSocketPath)));
    }
    return builder
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)

        // We only use http to talk to the agent
        .connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT))

        // We don't do async so this shouldn't matter, but just to be safe...
        .dispatcher(new Dispatcher(CommonTaskExecutor.INSTANCE))
        .build();
  }

  private static HttpUrl getUrl(final String host, final int port, final String endPoint) {
    return new HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addEncodedPathSegments(endPoint)
        .build();
  }

  private static Request.Builder prepareRequest(final HttpUrl url) {
    final Request.Builder builder =
        new Request.Builder()
            .url(url)
            .addHeader(DATADOG_META_LANG, "java")
            .addHeader(DATADOG_META_LANG_VERSION, DDTraceCoreInfo.JAVA_VERSION)
            .addHeader(DATADOG_META_LANG_INTERPRETER, DDTraceCoreInfo.JAVA_VM_NAME)
            .addHeader(DATADOG_META_LANG_INTERPRETER_VENDOR, DDTraceCoreInfo.JAVA_VM_VENDOR)
            .addHeader(DATADOG_META_TRACER_VERSION, DDTraceCoreInfo.VERSION);

    final String containerId = ContainerInfo.get().getContainerId();
    if (containerId == null) {
      return builder;
    } else {
      return builder.addHeader(DATADOG_CONTAINER_ID, containerId);
    }
  }

  void detectEndpointAndBuildClient() {
    if (httpClient == null) {
      final HttpUrl v4Url = getUrl(host, port, TRACES_ENDPOINT_V4);
      if (endpointAvailable(v4Url, unixDomainSocketPath, timeoutMillis, true)) {
        tracesUrl = v4Url;
      } else {
        log.debug("API v0.4 endpoints not available. Downgrading to v0.3");
        tracesUrl = getUrl(host, port, TRACES_ENDPOINT_V3);
      }
      httpClient = buildHttpClient(unixDomainSocketPath, timeoutMillis);
    }
  }

  /**
   * Encapsulates an attempted response from the Datadog agent.
   *
   * <p>If communication fails or times out, the Response will NOT be successful and will lack
   * status code, but will have an exception.
   *
   * <p>If an communication occurs, the Response will have a status code and will be marked as
   * success or fail in accordance with the code.
   *
   * <p>NOTE: A successful communication may still contain an exception if there was a problem
   * parsing the response from the Datadog agent.
   */
  public static final class Response {
    /** Factory method for a successful request with a trivial response body */
    public static Response success(final int status) {
      return new Response(true, status, null);
    }

    /** Factory method for a successful request will a malformed response body */
    public static Response success(final int status, final Throwable exception) {
      return new Response(true, status, exception);
    }

    /** Factory method for a request that receive an error status in response */
    public static Response failed(final int status) {
      return new Response(false, status, null);
    }

    /** Factory method for a failed communication attempt */
    public static Response failed(final Throwable exception) {
      return new Response(false, null, exception);
    }

    private final boolean success;
    private final Integer status;
    private final Throwable exception;

    private Response(final boolean success, final Integer status, final Throwable exception) {
      this.success = success;
      this.status = status;
      this.exception = exception;
    }

    public final boolean success() {
      return success;
    }

    // TODO: DQH - In Java 8, switch to OptionalInteger
    public final Integer status() {
      return status;
    }

    // TODO: DQH - In Java 8, switch to Optional<Throwable>?
    public final Throwable exception() {
      return exception;
    }
  }

  private static class MsgPackRequestBody extends RequestBody {
    private final ByteBuffer traces;

    private MsgPackRequestBody(ByteBuffer traces) {
      this.traces = traces;
    }

    @Override
    public MediaType contentType() {
      return MSGPACK;
    }

    @Override
    public long contentLength() {
      return traces.limit() - traces.position();
    }

    @Override
    public void writeTo(final BufferedSink sink) throws IOException {
      sink.write(traces);
      sink.flush();
    }
  }
}
