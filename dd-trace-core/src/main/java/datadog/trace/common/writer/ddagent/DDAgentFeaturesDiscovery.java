package datadog.trace.common.writer.ddagent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.http.OkHttpUtils;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.core.monitor.Recording;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
public class DDAgentFeaturesDiscovery implements DroppingPolicy {

  private static final JsonAdapter<Map<String, Object>> RESPONSE_ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  public static final String V3_ENDPOINT = "v0.3/traces";
  public static final String V4_ENDPOINT = "v0.4/traces";
  public static final String V5_ENDPOINT = "v0.5/traces";

  private static final String V5_METRICS_ENDPOINT = "v0.5/stats";

  private final OkHttpClient client;
  private final HttpUrl agentBaseUrl;
  private final Recording discoveryTimer;

  private volatile String traceEndpoint;
  private volatile String metricsEndpoint;
  private volatile boolean supportsDropping;

  private final String[] traceEndpoints;
  private final String[] metricsEndpoints = {V5_METRICS_ENDPOINT};

  public DDAgentFeaturesDiscovery(
      OkHttpClient client, Monitoring monitoring, HttpUrl agentUrl, boolean enableV05Traces) {
    this.client = client;
    this.agentBaseUrl = agentUrl;
    this.traceEndpoints =
        enableV05Traces
            ? new String[] {V5_ENDPOINT, V4_ENDPOINT, V3_ENDPOINT}
            : new String[] {V4_ENDPOINT, V3_ENDPOINT};
    this.discoveryTimer = monitoring.newTimer("trace.agent.discovery.time");
  }

  public void discover() {
    // 1. try to fetch info about the agent, if the endpoint is there
    // 2. try to parse the response, if it can be parsed, finish
    // 3. fallback if the endpoint couldn't be found or the response couldn't be parsed
    try (Recording recording = discoveryTimer.start()) {
      boolean fallback = true;
      try (Response response =
          client
              .newCall(new Request.Builder().url(agentBaseUrl.resolve("info").url()).build())
              .execute()) {
        if (response.isSuccessful()) {
          fallback = !processInfoResponse(response.body().string());
        }
      } catch (Throwable error) {
        errorQueryingEndpoint("info", error);
      }
      if (fallback) {
        log.debug("Falling back to probing, client dropping will be disabled");
        this.metricsEndpoint = probeTracerMetricsEndpoint();
        this.traceEndpoint = probeTracesEndpoint();
      }
    }
  }

  private String probeTracerMetricsEndpoint() {
    String candidate = "v0.5/stats";
    try (Response response =
        client
            .newCall(
                new Request.Builder()
                    .put(OkHttpUtils.msgpackRequestBodyOf(Collections.<ByteBuffer>emptyList()))
                    .url(agentBaseUrl.resolve(candidate).url())
                    .build())
            .execute()) {
      if (response.code() != 404) {
        return candidate;
      }
    } catch (IOException e) {
      errorQueryingEndpoint(candidate, e);
    }
    log.debug("No metrics endpoint found, metrics will be disabled");
    return null;
  }

  private String probeTracesEndpoint() {
    for (String candidate : traceEndpoints) {
      try (Response response =
          client
              .newCall(
                  new Request.Builder()
                      .put(OkHttpUtils.msgpackRequestBodyOf(Collections.<ByteBuffer>emptyList()))
                      .url(agentBaseUrl.resolve(candidate))
                      .build())
              .execute()) {
        if (response.code() != 404) {
          return candidate;
        }
      } catch (IOException e) {
        errorQueryingEndpoint(candidate, e);
      }
    }
    return V3_ENDPOINT;
  }

  @SuppressWarnings("unchecked")
  private boolean processInfoResponse(String response) {
    try {
      Map<String, Object> map = RESPONSE_ADAPTER.fromJson(response);
      List<String> endpoints = ((List<String>) map.get("endpoints"));
      ListIterator<String> traceAgentSupportedEndpoints = endpoints.listIterator(endpoints.size());
      boolean traceEndpointFound = false;
      boolean metricsEndpointFound = false;
      while ((!traceEndpointFound || !metricsEndpointFound)
          && traceAgentSupportedEndpoints.hasPrevious()) {
        String traceAgentSupportedEndpoint = traceAgentSupportedEndpoints.previous();
        if (traceAgentSupportedEndpoint.startsWith("/")
            && traceAgentSupportedEndpoint.length() > 1) {
          traceAgentSupportedEndpoint = traceAgentSupportedEndpoint.substring(1);
        }
        if (!metricsEndpointFound) {
          for (int i = metricsEndpoints.length - 1; i >= 0; --i) {
            if (metricsEndpoints[i].equalsIgnoreCase(traceAgentSupportedEndpoint)) {
              this.metricsEndpoint = traceAgentSupportedEndpoint;
              metricsEndpointFound = true;
              break;
            }
          }
        }
        if (!traceEndpointFound) {
          for (int i = traceEndpoints.length - 1; i >= 0; --i) {
            if (traceEndpoints[i].equalsIgnoreCase(traceAgentSupportedEndpoint)) {
              this.traceEndpoint = traceAgentSupportedEndpoint;
              traceEndpointFound = true;
              break;
            }
          }
        }
      }
      Object canDrop = map.get("client_drop_p0s");
      this.supportsDropping =
          null != canDrop
              && ("true".equalsIgnoreCase(String.valueOf(canDrop)) || Boolean.TRUE.equals(canDrop));
      return true;
    } catch (Throwable error) {
      log.debug("Error parsing trace agent /info response", error);
    }
    return false;
  }

  public boolean supportsMetrics() {
    return null != metricsEndpoint;
  }

  public boolean supportsDropping() {
    return supportsDropping;
  }

  public String getMetricsEndpoint() {
    return metricsEndpoint;
  }

  public String getTraceEndpoint() {
    return traceEndpoint;
  }

  private void errorQueryingEndpoint(String endpoint, Throwable t) {
    log.debug("Error querying {} at {}", endpoint, agentBaseUrl, t);
  }

  @Override
  public boolean active() {
    return supportsDropping;
  }
}
