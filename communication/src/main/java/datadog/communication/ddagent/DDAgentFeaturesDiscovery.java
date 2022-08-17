package datadog.communication.ddagent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.DDAgentStatsDClientManager;
import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  public static final String V7_CONFIG_ENDPOINT = "v0.7/config";

  public static final String V01_DATASTREAMS_ENDPOINT = "v0.1/pipeline_stats";

  public static final String DATADOG_AGENT_STATE = "Datadog-Agent-State";

  public static final String DEBUGGER_ENDPOINT = "debugger/v1/input";

  private final OkHttpClient client;
  private final HttpUrl agentBaseUrl;
  private final Recording discoveryTimer;
  private final String[] traceEndpoints;
  private final String[] metricsEndpoints = {V6_METRICS_ENDPOINT};
  private final String[] configEndpoints = {V7_CONFIG_ENDPOINT};
  private final boolean metricsEnabled;
  private final String[] dataStreamsEndpoints = {V01_DATASTREAMS_ENDPOINT};

  private volatile String traceEndpoint;
  private volatile String metricsEndpoint;
  private volatile String dataStreamsEndpoint;
  private volatile boolean supportsDropping;
  private volatile String state;
  private volatile String configEndpoint;
  private volatile String debuggerEndpoint;
  private volatile String version;

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

  private void reset() {
    traceEndpoint = null;
    metricsEndpoint = null;
    supportsDropping = false;
    state = null;
    configEndpoint = null;
    debuggerEndpoint = null;
    version = null;
  }

  public void discover() {
    reset();
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
        supportsDropping = false;
        log.debug("Falling back to probing, client dropping will be disabled");
        // disable metrics unless the info endpoint is present, which prevents
        // sending metrics to 7.26.0, which has a bug in reporting metric origin
        metricsEndpoint = null;
      }

      // don't want to rewire the traces pipeline
      if (null == traceEndpoint) {
        traceEndpoint = probeTracesEndpoint(traceEndpoints);
      } else if (state == null || state.isEmpty()) {
        // Still need to probe so that state is correctly assigned
        probeTracesEndpoint(new String[] {traceEndpoint});
      }
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "discovered traceEndpoint={}, metricsEndpoint={}, supportsDropping={}, dataStreamsEndpoint={}",
          traceEndpoint,
          metricsEndpoint,
          supportsDropping,
          dataStreamsEndpoint);
    }
  }

  private String probeTracesEndpoint(String[] endpoints) {
    for (String candidate : endpoints) {
      try (Response response =
          client
              .newCall(
                  new Request.Builder()
                      .put(OkHttpUtils.msgpackRequestBodyOf(Collections.<ByteBuffer>emptyList()))
                      .url(agentBaseUrl.resolve(candidate))
                      .build())
              .execute()) {
        if (response.code() != 404) {
          state = response.header(DATADOG_AGENT_STATE);
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
      version = (String) map.get("version");
      Set<String> endpoints = new HashSet<>((List<String>) map.get("endpoints"));

      String foundMetricsEndpoint = null;
      if (metricsEnabled) {
        for (String endpoint : metricsEndpoints) {
          if (endpoints.contains(endpoint) || endpoints.contains("/" + endpoint)) {
            foundMetricsEndpoint = endpoint;
            break;
          }
        }
      }

      // This is done outside of the loop to set metricsEndpoint to null if not found
      metricsEndpoint = foundMetricsEndpoint;

      for (String endpoint : traceEndpoints) {
        if (endpoints.contains(endpoint) || endpoints.contains("/" + endpoint)) {
          traceEndpoint = endpoint;
          break;
        }
      }

      for (String endpoint : configEndpoints) {
        if (endpoints.contains(endpoint) || endpoints.contains("/" + endpoint)) {
          configEndpoint = endpoint;
          break;
        }
      }

      if (endpoints.contains(DEBUGGER_ENDPOINT) || endpoints.contains("/" + DEBUGGER_ENDPOINT)) {
        debuggerEndpoint = DEBUGGER_ENDPOINT;
      }

      String foundDatastreamsEndpoint = null;
      for (String endpoint : dataStreamsEndpoints) {
        if (endpoints.contains(endpoint) || endpoints.contains("/" + endpoint)) {
          foundDatastreamsEndpoint = endpoint;
          break;
        }
      }

      // This is done outside of the loop to set dataStreamsEndpoint to null if not found
      dataStreamsEndpoint = foundDatastreamsEndpoint;

      if (metricsEnabled) {
        Object canDrop = map.get("client_drop_p0s");
        supportsDropping =
            null != canDrop
                && ("true".equalsIgnoreCase(String.valueOf(canDrop))
                    || Boolean.TRUE.equals(canDrop));
      }
      try {
        state = Strings.sha256(response);
      } catch (NoSuchAlgorithmException ex) {
        log.debug("Failed to hash trace agent /info response. Will probe {}", traceEndpoint, ex);
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

  public boolean supportsDebugger() {
    return debuggerEndpoint != null;
  }

  boolean supportsDropping() {
    return supportsDropping;
  }

  public String getMetricsEndpoint() {
    return metricsEndpoint;
  }

  public String getTraceEndpoint() {
    return traceEndpoint;
  }

  public String getDataStreamsEndpoint() {
    return dataStreamsEndpoint;
  }

  public HttpUrl buildUrl(String endpoint) {
    return agentBaseUrl.resolve(endpoint);
  }

  public boolean supportsDataStreams() {
    return dataStreamsEndpoint != null;
  }

  public String getConfigEndpoint() {
    return configEndpoint;
  }

  public String getVersion() {
    return version;
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
