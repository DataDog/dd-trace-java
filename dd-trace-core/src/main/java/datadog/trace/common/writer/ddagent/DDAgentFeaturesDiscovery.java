package datadog.trace.common.writer.ddagent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.http.OkHttpUtils;
import datadog.trace.core.monitor.DDAgentStatsDClientManager;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.core.monitor.Recording;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDAgentFeaturesDiscovery implements DroppingPolicy {

  private static final Logger log = LoggerFactory.getLogger(DDAgentFeaturesDiscovery.class);

  private static final JsonAdapter<Map<String, Object>> RESPONSE_ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  public static final String V3_ENDPOINT = "v0.3/traces";
  public static final String V4_ENDPOINT = "v0.4/traces";
  public static final String V5_ENDPOINT = "v0.5/traces";

  public static final String V6_METRICS_ENDPOINT = "v0.6/stats";
  private static final String DATADOG_AGENT_STATE = "Datadog-Agent-State";

  private final OkHttpClient client;
  private final HttpUrl agentBaseUrl;
  private final Recording discoveryTimer;
  private final String[] traceEndpoints;
  private final String[] metricsEndpoints = {V6_METRICS_ENDPOINT};
  private final boolean metricsEnabled;

  private volatile String traceEndpoint;
  private volatile String metricsEndpoint;
  private volatile boolean supportsDropping;
  private volatile String state;

  public DDAgentFeaturesDiscovery(
      OkHttpClient client,
      Monitoring monitoring,
      HttpUrl agentUrl,
      boolean enableV05Traces,
      boolean metricsEnabled) {
    this.client = client;
    this.agentBaseUrl = agentUrl;
    this.metricsEnabled = metricsEnabled;
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
        this.supportsDropping = false;
        log.debug("Falling back to probing, client dropping will be disabled");
        // disable metrics unless the info endpoint is present, which prevents
        // sending metrics to 7.26.0, which has a bug in reporting metric origin
        this.metricsEndpoint = null;
        // don't want to rewire the traces pipeline
        if (null == traceEndpoint) {
          this.traceEndpoint = probeTracesEndpoint();
        }
      }
    }
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
          this.state = response.header(DATADOG_AGENT_STATE);
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
      discoverStatsDPort(map);
      List<String> endpoints = ((List<String>) map.get("endpoints"));
      ListIterator<String> traceAgentSupportedEndpoints = endpoints.listIterator(endpoints.size());
      boolean traceEndpointFound = false;
      boolean metricsEndpointFound = !metricsEnabled;
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
      if (metricsEnabled) {
        Object canDrop = map.get("client_drop_p0s");
        this.supportsDropping =
            null != canDrop
                && ("true".equalsIgnoreCase(String.valueOf(canDrop))
                    || Boolean.TRUE.equals(canDrop));
      }
      return true;
    } catch (Throwable error) {
      log.debug("Error parsing trace agent /info response", error);
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static void discoverStatsDPort(final Map<String, Object> info) {
    try {
      Map<String, ?> config = (Map<String, ?>) info.get("config");
      int statsdPort = ((Number) config.get("statsd_port")).intValue();
      DDAgentStatsDClientManager.setDefaultStatsDPort(statsdPort);
    } catch (Throwable ignore) {
      log.debug("statsd_port missing from trace agent /info response", ignore);
    }
  }

  public boolean supportsMetrics() {
    return metricsEnabled && null != metricsEndpoint;
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

  public String state() {
    return state;
  }

  @Override
  public boolean active() {
    return supportsMetrics() && supportsDropping;
  }
}
