package datadog.communication.ddagent;

import static datadog.communication.http.OkHttpUtils.DATADOG_CONTAINER_ID;
import static datadog.communication.http.OkHttpUtils.DATADOG_CONTAINER_TAGS_HASH;
import static datadog.communication.serialization.msgpack.MsgPackWriter.FIXARRAY;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableSet;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.common.container.ContainerInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.Recording;
import datadog.metrics.impl.statsd.DDAgentStatsDClientManager;
import datadog.trace.api.BaseHash;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.util.Strings;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
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

  // Currently all the endpoints that we probe expect a msgpack body of an array of arrays, v3/v4
  // arbitrary size and v5 two elements, so let's give them a two element array of empty arrays
  private static final byte[] PROBE_MESSAGE = {
    (byte) FIXARRAY | 2, (byte) FIXARRAY, (byte) FIXARRAY
  };

  public static final String V3_ENDPOINT = "v0.3/traces";
  public static final String V4_ENDPOINT = "v0.4/traces";
  public static final String V5_ENDPOINT = "v0.5/traces";

  public static final String V6_METRICS_ENDPOINT = "v0.6/stats";
  public static final String V7_CONFIG_ENDPOINT = "v0.7/config";

  public static final String V01_DATASTREAMS_ENDPOINT = "v0.1/pipeline_stats";

  public static final String V2_EVP_PROXY_ENDPOINT = "evp_proxy/v2/";
  public static final String V4_EVP_PROXY_ENDPOINT = "evp_proxy/v4/";

  public static final String DATADOG_AGENT_STATE = "Datadog-Agent-State";

  public static final String DEBUGGER_ENDPOINT_V1 = "debugger/v1/input";
  public static final String DEBUGGER_ENDPOINT_V2 = "debugger/v2/input";
  public static final String DEBUGGER_DIAGNOSTICS_ENDPOINT = "debugger/v1/diagnostics";

  public static final String TELEMETRY_PROXY_ENDPOINT = "telemetry/proxy/";

  private static final long MIN_FEATURE_DISCOVERY_INTERVAL_MILLIS = 60 * 1000;

  private final OkHttpClient client;
  private final HttpUrl agentBaseUrl;
  private final Recording discoveryTimer;
  private final String[] traceEndpoints;
  private final String[] metricsEndpoints = {V6_METRICS_ENDPOINT};
  private final String[] configEndpoints = {V7_CONFIG_ENDPOINT};
  private final boolean metricsEnabled;
  private final String[] dataStreamsEndpoints = {V01_DATASTREAMS_ENDPOINT};
  // ordered from most recent to least recent, as the logic will stick with the first one that is
  // available
  private final String[] evpProxyEndpoints = {V4_EVP_PROXY_ENDPOINT, V2_EVP_PROXY_ENDPOINT};
  private final String[] telemetryProxyEndpoints = {TELEMETRY_PROXY_ENDPOINT};

  private static class State {
    String traceEndpoint;
    String metricsEndpoint;
    String dataStreamsEndpoint;
    boolean supportsLongRunning;
    boolean supportsClientSideStats;
    boolean supportsDropping;
    String state;
    String configEndpoint;
    String debuggerLogEndpoint;
    String debuggerSnapshotEndpoint;
    String debuggerDiagnosticsEndpoint;
    String evpProxyEndpoint;
    String version;
    String telemetryProxyEndpoint;
    Set<String> peerTags = emptySet();
    long lastTimeDiscovered;
  }

  private volatile State discoveryState;

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
    this.discoveryState = new State();
  }

  /** Run feature discovery, unconditionally. */
  public void discover() {
    discoverIfOutdated(0);
  }

  /** Run feature discovery, if it was not run recently. */
  public void discoverIfOutdated() {
    discoverIfOutdated(getFeaturesDiscoveryMinDelayMillis());
  }

  protected long getFeaturesDiscoveryMinDelayMillis() {
    return MIN_FEATURE_DISCOVERY_INTERVAL_MILLIS;
  }

  private synchronized void discoverIfOutdated(final long maxElapsedMs) {
    final long now = System.currentTimeMillis();
    final long elapsed = now - discoveryState.lastTimeDiscovered;
    if (elapsed > maxElapsedMs) {
      final State newState = new State();
      doDiscovery(newState);
      newState.lastTimeDiscovered = now;
      // swap atomically states
      discoveryState = newState;
    }
  }

  private void doDiscovery(State newState) {
    // 1. try to fetch info about the agent, if the endpoint is there
    // 2. try to parse the response, if it can be parsed, finish
    // 3. fallback if the endpoint couldn't be found or the response couldn't be parsed
    try (Recording recording = discoveryTimer.start()) {
      boolean fallback = true;
      final Request.Builder requestBuilder =
          new Request.Builder().url(agentBaseUrl.resolve("info").url());
      final String containerId = ContainerInfo.get().getContainerId();
      if (containerId != null) {
        requestBuilder.header(DATADOG_CONTAINER_ID, containerId);
      }
      try (Response response = client.newCall(requestBuilder.build()).execute()) {
        if (response.isSuccessful()) {
          processInfoResponseHeaders(response);
          fallback = !processInfoResponse(newState, response.body().string());
        }
      } catch (Throwable error) {
        errorQueryingEndpoint("info", error);
      }
      if (fallback) {
        newState.supportsClientSideStats = false;
        newState.supportsLongRunning = false;
        log.debug("Falling back to probing, client dropping will be disabled");
        // disable metrics unless the info endpoint is present, which prevents
        // sending metrics to 7.26.0, which has a bug in reporting metric origin
        newState.metricsEndpoint = null;
      }

      // don't want to rewire the traces pipeline
      if (null == newState.traceEndpoint) {
        newState.traceEndpoint = probeTracesEndpoint(newState, traceEndpoints);
      } else if (newState.state == null || newState.state.isEmpty()) {
        // Still need to probe so that state is correctly assigned
        probeTracesEndpoint(newState, new String[] {newState.traceEndpoint});
      }
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "discovered traceEndpoint={}, metricsEndpoint={}, supportsDropping={}, supportsLongRunning={}, dataStreamsEndpoint={}, configEndpoint={}, evpProxyEndpoint={}, telemetryProxyEndpoint={}",
          newState.traceEndpoint,
          newState.metricsEndpoint,
          newState.supportsDropping,
          newState.supportsLongRunning,
          newState.dataStreamsEndpoint,
          newState.configEndpoint,
          newState.evpProxyEndpoint,
          newState.telemetryProxyEndpoint);
    }
  }

  private String probeTracesEndpoint(State newState, String[] endpoints) {
    for (String candidate : endpoints) {
      try (Response response =
          client
              .newCall(
                  new Request.Builder()
                      .put(
                          OkHttpUtils.msgpackRequestBodyOf(
                              singletonList(ByteBuffer.wrap(PROBE_MESSAGE))))
                      .url(agentBaseUrl.resolve(candidate))
                      .build())
              .execute()) {
        if (response.code() != 404) {
          newState.state = response.header(DATADOG_AGENT_STATE);
          return candidate;
        }
      } catch (Throwable e) {
        errorQueryingEndpoint(candidate, e);
      }
    }
    return V3_ENDPOINT;
  }

  private void processInfoResponseHeaders(Response response) {
    String newContainerTagsHash = response.header(DATADOG_CONTAINER_TAGS_HASH);
    if (newContainerTagsHash != null) {
      ContainerInfo containerInfo = ContainerInfo.get();
      synchronized (containerInfo) {
        if (!newContainerTagsHash.equals(containerInfo.getContainerTagsHash())) {
          containerInfo.setContainerTagsHash(newContainerTagsHash);
          BaseHash.recalcBaseHash(newContainerTagsHash);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private boolean processInfoResponse(State newState, String response) {
    try {
      Map<String, Object> map = RESPONSE_ADAPTER.fromJson(response);
      discoverStatsDPort(map);
      newState.version = (String) map.get("version");
      Set<String> endpoints = new HashSet<>((List<String>) map.get("endpoints"));

      String foundMetricsEndpoint = null;
      if (metricsEnabled) {
        for (String endpoint : metricsEndpoints) {
          if (containsEndpoint(endpoints, endpoint)) {
            foundMetricsEndpoint = endpoint;
            break;
          }
        }
      }

      // This is done outside of the loop to set metricsEndpoint to null if not found
      newState.metricsEndpoint = foundMetricsEndpoint;

      for (String endpoint : traceEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          newState.traceEndpoint = endpoint;
          break;
        }
      }

      for (String endpoint : configEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          newState.configEndpoint = endpoint;
          break;
        }
      }

      if (containsEndpoint(endpoints, DEBUGGER_ENDPOINT_V1)) {
        newState.debuggerLogEndpoint = DEBUGGER_ENDPOINT_V1;
      }
      // both debugger v2 and diagnostics endpoints are forwarding events to the DEBUGGER intake
      // because older agents support diagnostics from DD agent 7.49
      if (containsEndpoint(endpoints, DEBUGGER_ENDPOINT_V2)) {
        newState.debuggerSnapshotEndpoint = DEBUGGER_ENDPOINT_V2;
      } else if (containsEndpoint(endpoints, DEBUGGER_DIAGNOSTICS_ENDPOINT)) {
        newState.debuggerSnapshotEndpoint = DEBUGGER_DIAGNOSTICS_ENDPOINT;
      }
      if (containsEndpoint(endpoints, DEBUGGER_DIAGNOSTICS_ENDPOINT)) {
        newState.debuggerDiagnosticsEndpoint = DEBUGGER_DIAGNOSTICS_ENDPOINT;
      }

      for (String endpoint : dataStreamsEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          newState.dataStreamsEndpoint = endpoint;
          break;
        }
      }

      for (String endpoint : evpProxyEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          newState.evpProxyEndpoint = endpoint;
          break;
        }
      }

      for (String endpoint : telemetryProxyEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          newState.telemetryProxyEndpoint = endpoint;
          break;
        }
      }

      newState.supportsLongRunning =
          Boolean.TRUE.equals(map.getOrDefault("long_running_spans", false));

      if (metricsEnabled) {
        Object canDrop = map.get("client_drop_p0s");
        newState.supportsDropping =
            null != canDrop
                && ("true".equalsIgnoreCase(String.valueOf(canDrop))
                    || Boolean.TRUE.equals(canDrop));

        newState.supportsClientSideStats =
            newState.supportsDropping && !AgentVersion.isVersionBelow(newState.version, 7, 65, 0);

        Object peer_tags = map.get("peer_tags");
        newState.peerTags =
            peer_tags instanceof List
                ? unmodifiableSet(new HashSet<>((List<String>) peer_tags))
                : emptySet();
      }
      try {
        newState.state = Strings.sha256(response);
      } catch (NoSuchAlgorithmException ex) {
        log.debug(
            "Failed to hash trace agent /info response. Will probe {}", newState.traceEndpoint, ex);
      }
      return true;
    } catch (Throwable error) {
      log.debug("Error parsing trace agent /info response", error);
    }
    return false;
  }

  private static boolean containsEndpoint(Set<String> endpoints, String endpoint) {
    return endpoints.contains(endpoint) || endpoints.contains("/" + endpoint);
  }

  @SuppressWarnings("unchecked")
  private static void discoverStatsDPort(final Map<String, Object> info) {
    try {
      Map<String, ?> config = (Map<String, ?>) info.get("config");
      if (config == null) {
        log.debug("config missing from trace agent /info response");
        return;
      }
      final Object statsdPortObj = config.get("statsd_port");
      if (statsdPortObj == null) {
        log.debug("statsd_port missing from trace agent /info response");
        return;
      }
      int statsdPort = ((Number) statsdPortObj).intValue();
      DDAgentStatsDClientManager.setDefaultStatsDPort(statsdPort);
    } catch (Throwable ex) {
      log.debug("statsd_port missing from trace agent /info response", ex);
    }
  }

  public boolean supportsMetrics() {
    return metricsEnabled
        && null != discoveryState.metricsEndpoint
        && discoveryState.supportsClientSideStats;
  }

  public boolean supportsDebugger() {
    return discoveryState.debuggerLogEndpoint != null;
  }

  public String getDebuggerSnapshotEndpoint() {
    return discoveryState.debuggerSnapshotEndpoint;
  }

  public String getDebuggerLogEndpoint() {
    return discoveryState.debuggerLogEndpoint;
  }

  public boolean supportsDebuggerDiagnostics() {
    return discoveryState.debuggerDiagnosticsEndpoint != null;
  }

  public boolean supportsLongRunning() {
    return discoveryState.supportsLongRunning;
  }

  public Set<String> peerTags() {
    return discoveryState.peerTags;
  }

  public String getMetricsEndpoint() {
    return discoveryState.metricsEndpoint;
  }

  public String getTraceEndpoint() {
    return discoveryState.traceEndpoint;
  }

  public String getDataStreamsEndpoint() {
    return discoveryState.dataStreamsEndpoint;
  }

  public String getEvpProxyEndpoint() {
    return discoveryState.evpProxyEndpoint;
  }

  public HttpUrl buildUrl(String endpoint) {
    return agentBaseUrl.resolve(endpoint);
  }

  public boolean supportsDataStreams() {
    return discoveryState.dataStreamsEndpoint != null;
  }

  public boolean supportsEvpProxy() {
    return discoveryState.evpProxyEndpoint != null;
  }

  public boolean supportsContentEncodingHeadersWithEvpProxy() {
    // content encoding headers are supported in /v4 and above
    final String evpProxyEndpoint = discoveryState.evpProxyEndpoint;
    return evpProxyEndpoint != null && V4_EVP_PROXY_ENDPOINT.compareTo(evpProxyEndpoint) <= 0;
  }

  public String getConfigEndpoint() {
    return discoveryState.configEndpoint;
  }

  public String getVersion() {
    return discoveryState.version;
  }

  private void errorQueryingEndpoint(String endpoint, Throwable t) {
    log.debug(LogCollector.EXCLUDE_TELEMETRY, "Error querying {} at {}", endpoint, agentBaseUrl, t);
  }

  public String state() {
    return discoveryState.state;
  }

  @Override
  public boolean active() {
    return supportsMetrics();
  }

  public boolean supportsTelemetryProxy() {
    return discoveryState.telemetryProxyEndpoint != null;
  }
}
