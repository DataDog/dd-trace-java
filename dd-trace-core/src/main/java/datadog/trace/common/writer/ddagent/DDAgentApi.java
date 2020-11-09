package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.http.OkHttpUtils.buildHttpClient;
import static datadog.trace.core.http.OkHttpUtils.prepareRequest;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.RatelimitedLogger;
import datadog.trace.core.monitor.Counter;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.core.monitor.Recording;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** The API pointing to a DD agent */
@Slf4j
public class DDAgentApi {
  private static final String DATADOG_CLIENT_COMPUTED_STATS = "Datadog-Client-Computed-Stats";
  private static final String X_DATADOG_TRACE_COUNT = "X-Datadog-Trace-Count";
  private static final String V3_ENDPOINT = "v0.3/traces";
  private static final String V4_ENDPOINT = "v0.4/traces";
  private static final String V5_ENDPOINT = "v0.5/traces";
  private static final long NANOSECONDS_BETWEEN_ERROR_LOG = TimeUnit.MINUTES.toNanos(5);

  private final List<DDAgentResponseListener> responseListeners = new ArrayList<>();
  private final String[] endpoints;

  private boolean logNextSuccess = false;
  private long totalTraces = 0;
  private long receivedTraces = 0;
  private long sentTraces = 0;
  private long failedTraces = 0;

  private final Recording discoveryTimer;
  private final Recording sendPayloadTimer;
  private final Counter agentErrorCounter;

  private static final JsonAdapter<Map<String, Map<String, Number>>> RESPONSE_ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(
              Types.newParameterizedType(
                  Map.class,
                  String.class,
                  Types.newParameterizedType(Map.class, String.class, Double.class)));
  private static final MediaType MSGPACK = MediaType.get("application/msgpack");

  private static final Map<String, RequestBody> ENDPOINT_SNIFF_REQUESTS;

  static {
    Map<String, RequestBody> requests = new HashMap<>();
    requests.put(V5_ENDPOINT, RequestBody.create(MSGPACK, TraceMapperV0_5.EMPTY));
    requests.put(V4_ENDPOINT, RequestBody.create(MSGPACK, TraceMapperV0_4.EMPTY));
    requests.put(V3_ENDPOINT, RequestBody.create(MSGPACK, TraceMapperV0_4.EMPTY));
    ENDPOINT_SNIFF_REQUESTS = Collections.unmodifiableMap(requests);
  }

  private final String agentUrl;
  private final String unixDomainSocketPath;
  private final long timeoutMillis;
  private final boolean metricsReportingEnabled;
  private OkHttpClient httpClient;
  private HttpUrl tracesUrl;
  private String detectedVersion = null;
  private boolean agentRunning = false;
  private RatelimitedLogger ratelimitedLogger =
      new RatelimitedLogger(log, NANOSECONDS_BETWEEN_ERROR_LOG);

  public DDAgentApi(
      final String agentUrl,
      final String unixDomainSocketPath,
      final long timeoutMillis,
      final boolean enableV05Endpoint,
      final boolean metricsReportingEnabled,
      final Monitoring monitoring) {
    this.agentUrl = agentUrl;
    this.unixDomainSocketPath = unixDomainSocketPath;
    this.timeoutMillis = timeoutMillis;
    this.metricsReportingEnabled = metricsReportingEnabled;
    this.endpoints =
        enableV05Endpoint
            ? new String[] {V5_ENDPOINT, V4_ENDPOINT, V3_ENDPOINT}
            : new String[] {V4_ENDPOINT, V3_ENDPOINT};
    this.discoveryTimer = monitoring.newTimer("trace.agent.discovery.time");
    this.sendPayloadTimer = monitoring.newTimer("trace.agent.send.time");
    this.agentErrorCounter = monitoring.newCounter("trace.agent.error.counter");
  }

  public DDAgentApi(
      final String agentUrl,
      final String unixDomainSocketPath,
      final long timeoutMillis,
      final Monitoring monitoring) {
    this(agentUrl, unixDomainSocketPath, timeoutMillis, true, false, monitoring);
  }

  public void addResponseListener(final DDAgentResponseListener listener) {
    if (!responseListeners.contains(listener)) {
      responseListeners.add(listener);
    }
  }

  TraceMapper selectTraceMapper() {
    String endpoint = detectEndpointAndBuildClient();
    if (null == endpoint) {
      return null;
    }
    if (V5_ENDPOINT.equals(endpoint)) {
      return new TraceMapperV0_5();
    }
    return new TraceMapperV0_4();
  }

  Response sendSerializedTraces(final Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();
    if (null == httpClient) {
      detectEndpointAndBuildClient();
      if (null == httpClient) {
        log.error("No datadog agent detected");
        countAndLogFailedSend(
            payload.traceCount(), payload.representativeCount(), sizeInBytes, null, null);
        return Response.failed(agentRunning ? 404 : 503);
      }
    }

    try {
      final Request request =
          prepareRequest(tracesUrl)
              .addHeader(DATADOG_CLIENT_COMPUTED_STATS, metricsReportingEnabled ? "true" : "")
              .addHeader(X_DATADOG_TRACE_COUNT, Integer.toString(payload.representativeCount()))
              .put(new MsgPackRequestBody(payload))
              .build();
      this.totalTraces += payload.representativeCount();
      this.receivedTraces += payload.traceCount();
      try (final Recording recording = sendPayloadTimer.start();
          final okhttp3.Response response = httpClient.newCall(request).execute()) {
        if (response.code() != 200) {
          agentErrorCounter.incrementErrorCount(response.message(), payload.traceCount());
          countAndLogFailedSend(
              payload.traceCount(), payload.representativeCount(), sizeInBytes, response, null);
          return Response.failed(response.code());
        }
        countAndLogSuccessfulSend(payload.traceCount(), payload.representativeCount(), sizeInBytes);
        String responseString = null;
        try {
          responseString = getResponseBody(response);
          if (!"".equals(responseString) && !"OK".equalsIgnoreCase(responseString)) {
            final Map<String, Map<String, Number>> parsedResponse =
                RESPONSE_ADAPTER.fromJson(responseString);
            final String endpoint = tracesUrl.toString();
            for (final DDAgentResponseListener listener : responseListeners) {
              listener.onResponse(endpoint, parsedResponse);
            }
          }
          return Response.success(response.code(), responseString);
        } catch (final IOException e) {
          log.debug("Failed to parse DD agent response: {}", responseString, e);
          return Response.success(response.code(), e);
        }
      }
    } catch (final IOException e) {
      countAndLogFailedSend(
          payload.traceCount(), payload.representativeCount(), sizeInBytes, null, e);
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
    String agentError = getResponseBody(response);
    if (log.isDebugEnabled()) {
      String sendErrorString =
          createSendLogMessage(
              traceCount,
              representativeCount,
              sizeInBytes,
              agentError.isEmpty() ? "Error" : agentError);
      if (response != null) {
        log.debug(
            "{} Status: {}, Response: {}, Body: {}",
            sendErrorString,
            response.code(),
            response.message(),
            agentError);
      } else if (outer != null) {
        log.debug(sendErrorString, outer);
      } else {
        log.debug(sendErrorString);
      }
      return;
    }
    String sendErrorString =
        createSendLogMessage(
            traceCount,
            representativeCount,
            sizeInBytes,
            agentError.isEmpty() ? "Error" : agentError);
    boolean hasLogged;
    if (response != null) {
      hasLogged =
          ratelimitedLogger.warn(
              "{} Status: {} {}", sendErrorString, response.code(), response.message());
    } else if (outer != null) {
      hasLogged =
          ratelimitedLogger.warn(
              "{} {}: {}", sendErrorString, outer.getClass().getName(), outer.getMessage());
    } else {
      hasLogged = ratelimitedLogger.warn(sendErrorString);
    }
    if (hasLogged) {
      this.logNextSuccess = true;
    }
  }

  private static String getResponseBody(okhttp3.Response response) {
    if (response != null) {
      try {
        return response.body().string().trim();
      } catch (NullPointerException | IOException ignored) {
      }
    }
    return "";
  }

  private String createSendLogMessage(
      final int traceCount,
      final int representativeCount,
      final int sizeInBytes,
      final String prefix) {
    String sizeString = sizeInBytes > 1024 ? (sizeInBytes / 1024) + "KB" : sizeInBytes + "B";
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

  private static OkHttpClient buildClientIfAvailable(
      final String endpoint,
      final HttpUrl url,
      final String unixDomainSocketPath,
      final long timeoutMillis) {
    final OkHttpClient client = buildHttpClient(url.scheme(), unixDomainSocketPath, timeoutMillis);
    try {
      return validateClient(endpoint, client, url);
    } catch (final IOException e) {
      try {
        return validateClient(endpoint, client, url);
      } catch (IOException ignored) {
        log.debug("No connectivity to {}: {}", url, ignored.getMessage());
      }
    }
    return null;
  }

  private static OkHttpClient validateClient(String endpoint, OkHttpClient client, HttpUrl url)
      throws IOException {
    final RequestBody body = ENDPOINT_SNIFF_REQUESTS.get(endpoint);
    final Request request =
        prepareRequest(url).header(X_DATADOG_TRACE_COUNT, "0").put(body).build();
    try (final okhttp3.Response response = client.newCall(request).execute()) {
      if (response.code() == 200) {
        log.debug("connectivity to {} validated", url);
        return client;
      } else {
        log.debug("connectivity to {} not validated, response code={}", url, response.code());
      }
    }
    return null;
  }

  String detectEndpointAndBuildClient() {
    // TODO clean this up
    if (httpClient == null) {
      try (Recording recording = discoveryTimer.start()) {
        HttpUrl baseUrl = HttpUrl.get(agentUrl);
        this.agentRunning = isAgentRunning(baseUrl.host(), baseUrl.port(), timeoutMillis);
        // TODO should check agentRunning, but CoreTracerTest depends on being
        //  able to detect an endpoint without an open socket...
        for (String candidate : endpoints) {
          tracesUrl = baseUrl.newBuilder().addEncodedPathSegments(candidate).build();
          this.httpClient =
              buildClientIfAvailable(candidate, tracesUrl, unixDomainSocketPath, timeoutMillis);
          if (null != httpClient) {
            detectedVersion = candidate;
            log.debug("connected to agent {}", candidate);
            return candidate;
          } else {
            log.debug("API {} endpoints not available. Downgrading", candidate);
          }
        }
        if (null == tracesUrl) {
          log.error("no compatible agent detected");
        }
      } finally {
        discoveryTimer.flush();
      }
    } else {
      log.warn("No connectivity to datadog agent");
    }
    if (null == detectedVersion && log.isDebugEnabled()) {
      log.debug("Tried all of {}, no connectivity to datadog agent", Arrays.asList(endpoints));
    }
    return detectedVersion;
  }

  private boolean isAgentRunning(final String host, final int port, final long timeoutMillis) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), (int) timeoutMillis);
      log.debug("Agent connectivity ({}:{})", host, port);
      return true;
    } catch (IOException ex) {
      log.debug("No agent connectivity ({}:{})", host, port);
      return false;
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
      return new Response(true, status, null, null);
    }

    /** Factory method for a successful request with a trivial response body */
    public static Response success(final int status, String response) {
      return new Response(true, status, null, response);
    }

    /** Factory method for a successful request will a malformed response body */
    public static Response success(final int status, final Throwable exception) {
      return new Response(true, status, exception, null);
    }

    /** Factory method for a request that receive an error status in response */
    public static Response failed(final int status) {
      return new Response(false, status, null, null);
    }

    /** Factory method for a failed communication attempt */
    public static Response failed(final Throwable exception) {
      return new Response(false, null, exception, null);
    }

    private final boolean success;
    private final Integer status;
    private final Throwable exception;
    private final String response;

    private Response(
        final boolean success, final Integer status, final Throwable exception, String response) {
      this.success = success;
      this.status = status;
      this.exception = exception;
      this.response = response;
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

    public final String response() {
      return response;
    }
  }

  private static class MsgPackRequestBody extends RequestBody {

    private final Payload payload;

    private MsgPackRequestBody(Payload payload) {
      this.payload = payload;
    }

    @Override
    public MediaType contentType() {
      return MSGPACK;
    }

    @Override
    public long contentLength() {
      return payload.sizeInBytes();
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      payload.writeTo(sink);
    }
  }
}
