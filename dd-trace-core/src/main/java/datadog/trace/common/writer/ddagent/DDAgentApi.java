package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.prepareRequest;

import com.antithesis.sdk.Assert;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.monitor.Counter;
import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
import datadog.trace.api.Config;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.DDTraceCoreInfo;
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
public class DDAgentApi extends RemoteApi {

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

  public DDAgentApi(
      OkHttpClient client,
      HttpUrl agentUrl,
      DDAgentFeaturesDiscovery featuresDiscovery,
      Monitoring monitoring,
      boolean metricsEnabled) {
    super(false);
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
    
    // Antithesis: Track that agent API send is being exercised
    log.debug("ANTITHESIS_ASSERT: Verifying DDAgentApi trace sending is exercised (reachable) with {} traces", payload.traceCount());
    Assert.reachable("DDAgentApi trace sending is exercised", null);
    log.debug("ANTITHESIS_ASSERT: Checking if traces are being sent through DDAgentApi (sometimes) - count: {}", payload.traceCount());
    Assert.sometimes(
        payload.traceCount() > 0,
        "Traces are being sent through DDAgentApi",
        null);
    
    String tracesEndpoint = featuresDiscovery.getTraceEndpoint();
    if (null == tracesEndpoint) {
      featuresDiscovery.discoverIfOutdated();
      tracesEndpoint = featuresDiscovery.getTraceEndpoint();
      if (null == tracesEndpoint) {
        // Antithesis: Agent should always be detectable
        ObjectNode agentDetectionDetails = JsonNodeFactory.instance.objectNode();
        agentDetectionDetails.put("trace_count", payload.traceCount());
        agentDetectionDetails.put("payload_size_bytes", sizeInBytes);
        agentDetectionDetails.put("agent_url", agentUrl.toString());
        agentDetectionDetails.put("failure_reason", "agent_not_detected");
        
        log.debug("ANTITHESIS_ASSERT: Agent not detected (unreachable) - url: {}, traces: {}", agentUrl, payload.traceCount());
        Assert.unreachable(
            "Datadog agent should always be detected - agent communication failure",
            agentDetectionDetails);
        
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
                  (metricsEnabled && featuresDiscovery.supportsMetrics())
                          // Disabling the computation agent-side of the APM trace metrics by
                          // pretending it was already done by the library
                          || !Config.get().isApmTracingEnabled()
                      ? "true"
                      : "")
              .put(payload.toRequest())
              .build();
      this.totalTraces += payload.traceCount();
      this.receivedTraces += payload.traceCount();
      try (final Recording recording = sendPayloadTimer.start();
          final okhttp3.Response response = httpClient.newCall(request).execute()) {
        handleAgentChange(response.header(DATADOG_AGENT_STATE));
        
        // Antithesis: Track HTTP response status and assert success
        ObjectNode httpResponseDetails = JsonNodeFactory.instance.objectNode();
        httpResponseDetails.put("trace_count", payload.traceCount());
        httpResponseDetails.put("payload_size_bytes", sizeInBytes);
        httpResponseDetails.put("http_status", response.code());
        httpResponseDetails.put("http_message", response.message());
        httpResponseDetails.put("success", response.code() == 200);
        httpResponseDetails.put("agent_url", tracesUrl.toString());
        
        log.debug("ANTITHESIS_ASSERT: Checking HTTP response status (always) - code: {}, traces: {}", response.code(), payload.traceCount());
        Assert.always(
            response.code() == 200,
            "HTTP response from Datadog agent should always be 200 - API communication failure",
            httpResponseDetails);
        
        if (response.code() != 200) {
          // Antithesis: Mark non-200 path as unreachable
          ObjectNode errorDetails = JsonNodeFactory.instance.objectNode();
          errorDetails.put("trace_count", payload.traceCount());
          errorDetails.put("payload_size_bytes", sizeInBytes);
          errorDetails.put("http_status", response.code());
          errorDetails.put("http_message", response.message());
          errorDetails.put("failure_reason", "http_error_response");
          
          log.debug("ANTITHESIS_ASSERT: Non-200 HTTP response (unreachable) - code: {}, message: {}, traces: {}", response.code(), response.message(), payload.traceCount());
          Assert.unreachable(
              "Non-200 HTTP response from agent indicates API failure - traces may be lost",
              errorDetails);
          
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
      // Antithesis: Network failures should not occur
      ObjectNode networkErrorDetails = JsonNodeFactory.instance.objectNode();
      networkErrorDetails.put("trace_count", payload.traceCount());
      networkErrorDetails.put("payload_size_bytes", sizeInBytes);
      networkErrorDetails.put("exception_type", e.getClass().getName());
      networkErrorDetails.put("exception_message", e.getMessage());
      networkErrorDetails.put("agent_url", agentUrl.toString());
      networkErrorDetails.put("failure_reason", "network_io_exception");
      
      log.debug("ANTITHESIS_ASSERT: Network/IO exception (unreachable) - type: {}, message: {}, traces: {}", e.getClass().getName(), e.getMessage(), payload.traceCount());
      Assert.unreachable(
          "Network/IO exceptions should not occur when sending to agent - indicates connectivity issues",
          networkErrorDetails);
      
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

  @Override
  protected Logger getLogger() {
    return log;
  }

  public void setHeader(String k, String v) {
    this.headers.put(k, v);
  }
}
