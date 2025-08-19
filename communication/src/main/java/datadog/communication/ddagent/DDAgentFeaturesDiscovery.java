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
import datadog.communication.monitor.DDAgentStatsDClientManager;
import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
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

  public static final String DEBUGGER_ENDPOINT = "debugger/v1/input";
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

  private volatile String traceEndpoint;
  private volatile String metricsEndpoint;
  private volatile String dataStreamsEndpoint;
  private volatile boolean supportsLongRunning;
  private volatile boolean supportsDropping;
  private volatile String state;
  private volatile String configEndpoint;
  private volatile String debuggerEndpoint;
  private volatile String debuggerDiagnosticsEndpoint;
  private volatile String evpProxyEndpoint;
  private volatile String version;
  private volatile String telemetryProxyEndpoint;
  private volatile Set<String> peerTags = emptySet();
  private volatile Set<String> spanKindsToComputedStats = emptySet();

  private long lastTimeDiscovered;

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
    supportsLongRunning = false;
    state = null;
    configEndpoint = null;
    debuggerEndpoint = null;
    debuggerDiagnosticsEndpoint = null;
    dataStreamsEndpoint = null;
    evpProxyEndpoint = null;
    version = null;
    lastTimeDiscovered = 0;
    telemetryProxyEndpoint = null;
    peerTags = emptySet();
    spanKindsToComputedStats = emptySet();
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
    final long elapsed = now - lastTimeDiscovered;
    if (elapsed > maxElapsedMs) {
      doDiscovery();
      lastTimeDiscovered = now;
    }
  }

  private void doDiscovery() {
    reset();
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
          fallback = !processInfoResponse(response.body().string());
        }
      } catch (Throwable error) {
        errorQueryingEndpoint("info", error);
      }
      if (fallback) {
        supportsDropping = false;
        supportsLongRunning = false;
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
          "discovered traceEndpoint={}, metricsEndpoint={}, supportsDropping={}, supportsLongRunning={}, dataStreamsEndpoint={}, configEndpoint={}, evpProxyEndpoint={}, telemetryProxyEndpoint={}",
          traceEndpoint,
          metricsEndpoint,
          supportsDropping,
          supportsLongRunning,
          dataStreamsEndpoint,
          configEndpoint,
          evpProxyEndpoint,
          telemetryProxyEndpoint);
    }
  }

  private String probeTracesEndpoint(String[] endpoints) {
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
          state = response.header(DATADOG_AGENT_STATE);
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
      if (!newContainerTagsHash.equals(containerInfo.getContainerTagsHash())) {
        containerInfo.setContainerTagsHash(newContainerTagsHash);
        BaseHash.recalcBaseHash(newContainerTagsHash);
      }
    }
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
          if (containsEndpoint(endpoints, endpoint)) {
            foundMetricsEndpoint = endpoint;
            break;
          }
        }
      }

      // This is done outside of the loop to set metricsEndpoint to null if not found
      metricsEndpoint = foundMetricsEndpoint;

      for (String endpoint : traceEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          traceEndpoint = endpoint;
          break;
        }
      }

      for (String endpoint : configEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          configEndpoint = endpoint;
          break;
        }
      }

      if (containsEndpoint(endpoints, DEBUGGER_ENDPOINT)) {
        debuggerEndpoint = DEBUGGER_ENDPOINT;
      }
      if (containsEndpoint(endpoints, DEBUGGER_DIAGNOSTICS_ENDPOINT)) {
        debuggerDiagnosticsEndpoint = DEBUGGER_DIAGNOSTICS_ENDPOINT;
      }

      for (String endpoint : dataStreamsEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          dataStreamsEndpoint = endpoint;
          break;
        }
      }

      for (String endpoint : evpProxyEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          evpProxyEndpoint = endpoint;
          break;
        }
      }

      for (String endpoint : telemetryProxyEndpoints) {
        if (containsEndpoint(endpoints, endpoint)) {
          telemetryProxyEndpoint = endpoint;
          break;
        }
      }

      supportsLongRunning = Boolean.TRUE.equals(map.getOrDefault("long_running_spans", false));

      if (metricsEnabled) {
        Object canDrop = map.get("client_drop_p0s");
        supportsDropping =
            null != canDrop
                && ("true".equalsIgnoreCase(String.valueOf(canDrop))
                    || Boolean.TRUE.equals(canDrop));

        Object peer_tags = map.get("peer_tags");
        peerTags =
            peer_tags instanceof List
                ? unmodifiableSet(new HashSet<>((List<String>) peer_tags))
                : emptySet();

        Object span_kinds = map.get("span_kinds_stats_computed");
        spanKindsToComputedStats =
            span_kinds instanceof List
                ? unmodifiableSet(new HashSet<>((List<String>) span_kinds))
                : emptySet();
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
    return metricsEnabled && null != metricsEndpoint;
  }

  public boolean supportsDebugger() {
    return debuggerEndpoint != null;
  }

  public boolean supportsDebuggerDiagnostics() {
    return debuggerDiagnosticsEndpoint != null;
  }

  public boolean supportsDropping() {
    return supportsDropping;
  }

  public boolean supportsLongRunning() {
    return supportsLongRunning;
  }

  public Set<String> peerTags() {
    return peerTags;
  }

  public Set<String> spanKindsToComputedStats() {
    return spanKindsToComputedStats;
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

  public String getEvpProxyEndpoint() {
    return evpProxyEndpoint;
  }

  public HttpUrl buildUrl(String endpoint) {
    return agentBaseUrl.resolve(endpoint);
  }

  public boolean supportsDataStreams() {
    return dataStreamsEndpoint != null;
  }

  public boolean supportsEvpProxy() {
    return evpProxyEndpoint != null;
  }

  public boolean supportsContentEncodingHeadersWithEvpProxy() {
    // content encoding headers are supported in /v4 and above
    return evpProxyEndpoint != null && V4_EVP_PROXY_ENDPOINT.compareTo(evpProxyEndpoint) <= 0;
  }

  public String getConfigEndpoint() {
    return configEndpoint;
  }

  public String getVersion() {
    return version;
  }

  private void errorQueryingEndpoint(String endpoint, Throwable t) {
    log.debug(LogCollector.EXCLUDE_TELEMETRY, "Error querying {} at {}", endpoint, agentBaseUrl, t);
  }

  public String state() {
    return state;
  }

  @Override
  public boolean active() {
    return supportsMetrics() && supportsDropping;
  }

  public boolean supportsTelemetryProxy() {
    return telemetryProxyEndpoint != null;
  }
}
