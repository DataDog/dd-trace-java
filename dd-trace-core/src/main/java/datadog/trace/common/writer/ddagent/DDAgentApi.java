package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.http.OkHttpUtils.buildHttpClient;
import static datadog.trace.core.http.OkHttpUtils.prepareRequest;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.IOLogger;
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
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** The API pointing to a DD agent */
@Slf4j
public class DDAgentApi implements TraceAPI {
  private static final String DATADOG_CLIENT_COMPUTED_STATS = "Datadog-Client-Computed-Stats";
  // this is not intended to be a toggled feature,
  // rather it identifies this tracer as one which has computed top level status
  private static final String DATADOG_CLIENT_COMPUTED_TOP_LEVEL =
      "Datadog-Client-Computed-Top-Level";
  private static final String X_DATADOG_TRACE_COUNT = "X-Datadog-Trace-Count";
  private static final String V3_ENDPOINT = "v0.3/traces";
  private static final String V4_ENDPOINT = "v0.4/traces";
  private static final String V5_ENDPOINT = "v0.5/traces";

  private final List<DDAgentResponseListener> responseListeners = new ArrayList<>();
  private final String[] endpoints;

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
  private final long timeoutMillis;
  private final boolean metricsReportingEnabled;
  private final OkHttpClient httpClient;
  private HttpUrl tracesUrl;
  private String detectedVersion = null;
  private boolean agentRunning = false;
  private boolean agentDiscovered = false;
  private boolean usingUnixDomainSockets;
  private final IOLogger ioLogger = new IOLogger(log);

  public DDAgentApi(
      final String agentUrl,
      final String unixDomainSocketPath,
      final long timeoutMillis,
      final boolean enableV05Endpoint,
      final boolean metricsReportingEnabled,
      final Monitoring monitoring) {
    this.agentUrl = agentUrl;
    this.usingUnixDomainSockets = unixDomainSocketPath != null;
    this.timeoutMillis = timeoutMillis;
    this.metricsReportingEnabled = metricsReportingEnabled;
    this.httpClient = buildHttpClient(HttpUrl.get(agentUrl), unixDomainSocketPath, timeoutMillis);
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

  @Override
  public TraceMapper selectTraceMapper() {
    String endpoint = detectEndpoint();
    if (null == endpoint) {
      return null;
    }
    if (V5_ENDPOINT.equals(endpoint)) {
      return new TraceMapperV0_5();
    }
    return new TraceMapperV0_4();
  }

  @Override
  public Response sendSerializedTraces(final Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();
    if (!agentDiscovered) {
      detectEndpoint();
      if (!agentDiscovered) {
        log.error("No datadog agent detected");
        countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, null);
        return Response.failed(agentRunning ? 404 : 503);
      }
    }

    try {
      final Request request =
          prepareRequest(tracesUrl)
              .addHeader(DATADOG_CLIENT_COMPUTED_TOP_LEVEL, "true")
              .addHeader(DATADOG_CLIENT_COMPUTED_STATS, metricsReportingEnabled ? "true" : "")
              .addHeader(X_DATADOG_TRACE_COUNT, Integer.toString(payload.traceCount()))
              .put(payload.toRequest())
              .build();
      this.totalTraces += payload.traceCount();
      this.receivedTraces += payload.traceCount();
      try (final Recording recording = sendPayloadTimer.start();
          final okhttp3.Response response = httpClient.newCall(request).execute()) {
        if (response.code() != 200) {
          agentErrorCounter.incrementErrorCount(response.message(), payload.traceCount());
          countAndLogFailedSend(payload.traceCount(), sizeInBytes, response, null);
          return Response.failed(response.code());
        }
        countAndLogSuccessfulSend(payload.traceCount(), sizeInBytes);
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
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  private void countAndLogSuccessfulSend(final int traceCount, final int sizeInBytes) {
    // count the successful traces
    this.sentTraces += traceCount;

    ioLogger.success(createSendLogMessage(traceCount, sizeInBytes, "Success"));
  }

  private void countAndLogFailedSend(
      final int traceCount,
      final int sizeInBytes,
      final okhttp3.Response response,
      final IOException outer) {
    // count the failed traces
    this.failedTraces += traceCount;
    // these are used to catch and log if there is a failure in debug logging the response body
    String agentError = getResponseBody(response);
    String sendErrorString =
        createSendLogMessage(traceCount, sizeInBytes, agentError.isEmpty() ? "Error" : agentError);

    ioLogger.error(sendErrorString, toLoggerResponse(response, agentError), outer);
  }

  private static IOLogger.Response toLoggerResponse(okhttp3.Response response, String body) {
    if (response == null) {
      return null;
    }
    return new IOLogger.Response(response.code(), response.message(), body);
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
      final int traceCount, final int sizeInBytes, final String prefix) {
    String sizeString = sizeInBytes > 1024 ? (sizeInBytes / 1024) + "KB" : sizeInBytes + "B";
    return prefix
        + " while sending "
        + traceCount
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

  private static boolean validateClient(String endpoint, OkHttpClient client, HttpUrl url) {
    final RequestBody body = ENDPOINT_SNIFF_REQUESTS.get(endpoint);
    final Request request =
        prepareRequest(url).header(X_DATADOG_TRACE_COUNT, "0").put(body).build();
    try (final okhttp3.Response response = client.newCall(request).execute()) {
      if (response.code() == 200) {
        log.debug("connectivity to {} validated", url);
        return true;
      } else {
        log.debug("connectivity to {} not validated, response code={}", url, response.code());
      }
    } catch (IOException e) {
      log.debug("failed to connect to datadog agent endpoint {}", endpoint, e);
    }
    return false;
  }

  String detectEndpoint() {
    // TODO clean this up
    if (!agentDiscovered) {
      try (Recording recording = discoveryTimer.start()) {
        HttpUrl baseUrl = HttpUrl.get(agentUrl);
        this.agentRunning = isAgentRunning(baseUrl.host(), baseUrl.port(), timeoutMillis);
        // TODO should check agentRunning, but CoreTracerTest depends on being
        //  able to detect an endpoint without an open socket...
        for (String candidate : endpoints) {
          tracesUrl = baseUrl.newBuilder().addEncodedPathSegments(candidate).build();
          if (validateClient(candidate, httpClient, HttpUrl.get(agentUrl).resolve(candidate))) {
            this.agentDiscovered = true;
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
}
