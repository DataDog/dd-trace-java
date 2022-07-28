package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.prepareRequest;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.monitor.Counter;
import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.relocate.api.IOLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The API pointing to a DD agent */
public class DDAgentApi implements RemoteApi {

  public static final String DATADOG_META_TRACER_VERSION = "Datadog-Meta-Tracer-Version";
  private static final Logger log = LoggerFactory.getLogger(DDAgentApi.class);

  private static final String DATADOG_CLIENT_COMPUTED_STATS = "Datadog-Client-Computed-Stats";
  // this is not intended to be a toggled feature,
  // rather it identifies this tracer as one which has computed top level status
  private static final String DATADOG_CLIENT_COMPUTED_TOP_LEVEL =
      "Datadog-Client-Computed-Top-Level";
  private static final String X_DATADOG_TRACE_COUNT = "X-Datadog-Trace-Count";
  private static final String DATADOG_DROPPED_TRACE_COUNT = "Datadog-Client-Dropped-P0-Traces";
  private static final String DATADOG_DROPPED_SPAN_COUNT = "Datadog-Client-Dropped-P0-Spans";
  private static final String DATADOG_AGENT_STATE = "Datadog-Agent-State";

  private final List<RemoteResponseListener> responseListeners = new ArrayList<>();
  private final boolean metricsEnabled;

  private long totalTraces = 0;
  private long receivedTraces = 0;
  private long sentTraces = 0;
  private long failedTraces = 0;

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

  private final DDAgentFeaturesDiscovery featuresDiscovery;
  private final OkHttpClient httpClient;
  private final HttpUrl agentUrl;
  private final Map<String, String> headers;

  private final IOLogger ioLogger = new IOLogger(log);

  public DDAgentApi(
      OkHttpClient client,
      HttpUrl agentUrl,
      DDAgentFeaturesDiscovery featuresDiscovery,
      Monitoring monitoring,
      boolean metricsEnabled) {
    this.featuresDiscovery = featuresDiscovery;
    this.agentUrl = agentUrl;
    this.httpClient = client;
    this.sendPayloadTimer = monitoring.newTimer("trace.agent.send.time");
    this.agentErrorCounter = monitoring.newCounter("trace.agent.error.counter");
    this.metricsEnabled = metricsEnabled;

    this.headers = new HashMap<>();
    this.headers.put(DATADOG_CLIENT_COMPUTED_TOP_LEVEL, "true");
    this.headers.put(DATADOG_META_TRACER_VERSION, DDTraceCoreInfo.VERSION);
  }

  public void addResponseListener(final RemoteResponseListener listener) {
    if (!responseListeners.contains(listener)) {
      responseListeners.add(listener);
    }
  }

  public Response sendSerializedTraces(final Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();
    String tracesEndpoint = featuresDiscovery.getTraceEndpoint();
    if (null == tracesEndpoint) {
      featuresDiscovery.discover();
      tracesEndpoint = featuresDiscovery.getTraceEndpoint();
      if (null == tracesEndpoint) {
        log.error("No datadog agent detected");
        countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, null);
        return Response.failed(404);
      }
    }

    HttpUrl tracesUrl = agentUrl.resolve(tracesEndpoint);
    try {
      final Request request =
          prepareRequest(tracesUrl, headers)
              .addHeader(X_DATADOG_TRACE_COUNT, Integer.toString(payload.traceCount()))
              .addHeader(DATADOG_DROPPED_TRACE_COUNT, Long.toString(payload.droppedTraces()))
              .addHeader(DATADOG_DROPPED_SPAN_COUNT, Long.toString(payload.droppedSpans()))
              .addHeader(
                  DATADOG_CLIENT_COMPUTED_STATS,
                  metricsEnabled && featuresDiscovery.supportsMetrics() ? "true" : "")
              .put(payload.toRequest())
              .build();
      this.totalTraces += payload.traceCount();
      this.receivedTraces += payload.traceCount();
      try (final Recording recording = sendPayloadTimer.start();
          final okhttp3.Response response = httpClient.newCall(request).execute()) {
        handleAgentChange(response.header(DATADOG_AGENT_STATE));
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
            for (final RemoteResponseListener listener : responseListeners) {
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

  private void handleAgentChange(String state) {
    String previous = featuresDiscovery.state();
    if (!Objects.equals(state, previous)) {
      featuresDiscovery.discover();
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
}
