package datadog.communication.ddagent

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V04_ENDPOINT
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V05_ENDPOINT
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V07_CONFIG_ENDPOINT
import static datadog.communication.http.HttpUtils.DATADOG_CONTAINER_ID
import static datadog.communication.http.HttpUtils.DATADOG_CONTAINER_TAGS_HASH

import datadog.common.container.ContainerInfo
import datadog.http.client.HttpClient
import datadog.http.client.HttpRequest
import datadog.http.client.HttpUrl
import datadog.http.client.okhttp.OkHttpResponse
import datadog.metrics.api.Monitoring
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.Strings
import java.nio.file.Files
import java.nio.file.Paths
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import spock.lang.Shared

class DDAgentFeaturesDiscoveryTest extends DDSpecification {

  @Shared
  Monitoring monitoring = Monitoring.DISABLED

  @Shared
  HttpUrl agentUrl = HttpUrl.parse("http://localhost:8125")

  static final String INFO_RESPONSE = loadJsonFile("agent-info.json")
  static final String INFO_STATE = Strings.sha256(INFO_RESPONSE)
  static final String INFO_WITH_PEER_TAG_BACK_PROPAGATION_RESPONSE = loadJsonFile("agent-info-with-peer-tag-back-propagation.json")
  static final String INFO_WITH_PEER_TAG_BACK_PROPAGATION_STATE = Strings.sha256(INFO_WITH_PEER_TAG_BACK_PROPAGATION_RESPONSE)
  static final String INFO_WITH_CLIENT_DROPPING_RESPONSE = loadJsonFile("agent-info-with-client-dropping.json")
  static final String INFO_WITH_CLIENT_DROPPING_STATE = Strings.sha256(INFO_WITH_CLIENT_DROPPING_RESPONSE)
  static final String INFO_WITHOUT_METRICS_RESPONSE = loadJsonFile("agent-info-without-metrics.json")
  static final String INFO_WITHOUT_METRICS_STATE = Strings.sha256(INFO_WITHOUT_METRICS_RESPONSE)
  static final String INFO_WITHOUT_DATA_STREAMS_RESPONSE = loadJsonFile("agent-info-without-data-streams.json")
  static final String INFO_WITHOUT_DATA_STREAMS_STATE = Strings.sha256(INFO_WITHOUT_DATA_STREAMS_RESPONSE)
  static final String INFO_WITH_LONG_RUNNING_SPANS = loadJsonFile("agent-info-with-long-running-spans.json")
  static final String INFO_WITH_TELEMETRY_PROXY_RESPONSE = loadJsonFile("agent-info-with-telemetry-proxy.json")
  static final String INFO_WITH_OLD_EVP_PROXY = loadJsonFile("agent-info-with-old-evp-proxy.json")
  static final String PROBE_STATE = "probestate"

  def "test parse /info response"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, v05Enabled, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_RESPONSE) }
    features.getMetricsEndpoint() == V06_METRICS_ENDPOINT
    !features.supportsMetrics()
    features.getTraceEndpoint() == expectedTraceEndpoint
    features.getDataStreamsEndpoint() == V01_DATASTREAMS_ENDPOINT
    features.supportsDataStreams()
    features.state() == INFO_STATE
    features.getConfigEndpoint() == V07_CONFIG_ENDPOINT
    features.supportsDebugger()
    features.getDebuggerSnapshotEndpoint() == "debugger/v2/input"
    features.supportsDebuggerDiagnostics()
    features.supportsEvpProxy()
    features.supportsContentEncodingHeadersWithEvpProxy()
    features.getEvpProxyEndpoint() == "evp_proxy/v4/"
    features.getVersion() == "0.99.0"
    !features.supportsLongRunning()
    !features.supportsTelemetryProxy()
    0 * _

    where:
    v05Enabled | expectedTraceEndpoint
    false      | V04_ENDPOINT
    true       | V05_ENDPOINT
  }

  def "Should change discovery state atomically after discovery happened"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then: "info returned"
    1 * client.execute(_) >> {
      HttpRequest request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE)
    }
    features.supportsMetrics()

    when: "discovery again"
    features.discover()

    then: "should continue having metrics discovered while discovering"
    1 * client.execute(_) >> {
      HttpRequest request -> {
        assert features.supportsMetrics(): "metrics should stay supported until the discovery has finished"
        infoResponse(request, INFO_RESPONSE)
      }
    }
  }

  def "test parse /info response with discoverIfOutdated"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discoverIfOutdated()
    features.discoverIfOutdated()
    features.discoverIfOutdated()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_RESPONSE) }
    features.getMetricsEndpoint() == V06_METRICS_ENDPOINT
    !features.supportsMetrics()
    features.getTraceEndpoint() == V05_ENDPOINT
    features.getDataStreamsEndpoint() == V01_DATASTREAMS_ENDPOINT
    features.supportsDataStreams()
    features.state() == INFO_STATE
    features.getConfigEndpoint() == V07_CONFIG_ENDPOINT
    features.supportsDebugger()
    features.supportsDebuggerDiagnostics()
    features.supportsEvpProxy()
    features.getVersion() == "0.99.0"
    !features.supportsLongRunning()
    !features.supportsTelemetryProxy()
    0 * _
  }

  def "test parse /info response with client dropping"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    features.getMetricsEndpoint() == V06_METRICS_ENDPOINT
    features.supportsMetrics()
    features.getTraceEndpoint() == V05_ENDPOINT
    features.state() == INFO_WITH_CLIENT_DROPPING_STATE
    0 * _
  }


  def "test parse /info response with data streams unavailable"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_WITHOUT_DATA_STREAMS_RESPONSE) }
    features.getMetricsEndpoint() == V06_METRICS_ENDPOINT
    !features.supportsMetrics()
    features.getTraceEndpoint() == V05_ENDPOINT
    features.getDataStreamsEndpoint() == null
    !features.supportsDataStreams()
    features.state() == INFO_WITHOUT_DATA_STREAMS_STATE
    0 * _
  }

  def "test parse /info response with long running spans available"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_WITH_LONG_RUNNING_SPANS) }
    features.supportsLongRunning()
    0 * _
  }

  def "test fallback when /info not found"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { HttpRequest request -> clientError(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.3/traces" }) >> { HttpRequest request -> success(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == V05_ENDPOINT
    !features.supportsLongRunning()
    features.state() == PROBE_STATE
    0 * _
  }

  def "test fallback when /info not found and agent returns ok"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { HttpRequest request -> success(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { HttpRequest request -> success(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == V05_ENDPOINT
    !features.supportsLongRunning()
    features.state() == PROBE_STATE
    0 * _
  }

  def "test fallback when /info not found and v0.5 disabled"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { HttpRequest request -> clientError(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { HttpRequest request -> success(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.3/traces" }) >> { HttpRequest request -> success(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    features.state() == PROBE_STATE
    0 * _
  }

  def "test fallback when /info not found and v0.5 unavailable agent side"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { HttpRequest request -> clientError(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { HttpRequest request -> notFound(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.3/traces" }) >> { HttpRequest request -> success(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    features.state() == PROBE_STATE
    0 * _
  }

  def "test fallback on very old agent"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { HttpRequest request -> notFound(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { HttpRequest request -> notFound(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> notFound(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.3/traces" }) >> { HttpRequest request -> success(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.3/traces"
    !features.supportsLongRunning()
    features.state() == PROBE_STATE
    0 * _
  }

  def "disabling metrics disables metrics and dropping"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, false)

    when: "/info unavailable"
    features.discover()

    then: "metrics endpoint not probed, metrics and dropping not supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.6/stats" })
    !features.supportsMetrics()
    !(features as DroppingPolicy).active()
    features.state() == PROBE_STATE

    when: "/info available and agent allows dropping"
    features.discover()

    then: "metrics and dropping not supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    !features.supportsMetrics()
    !(features as DroppingPolicy).active()
    features.state() == INFO_WITH_CLIENT_DROPPING_STATE

    when: "/info available and agent does not allow dropping"
    features.discover()

    then: "metrics and dropping not supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> infoResponse(request, INFO_RESPONSE) }
    !features.supportsMetrics()
    !(features as DroppingPolicy).active()
    features.state() == INFO_STATE
    0 * _
  }

  def "discovery of metrics endpoint after agent upgrade enables dropping and metrics"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true)

    when: "/info unavailable"
    features.discover()

    then: "metrics endpoint not probed, metrics and dropping not supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.6/stats" })
    !features.supportsMetrics()
    !(features as DroppingPolicy).active()
    features.state() == PROBE_STATE

    when: "/info and v0.6/stats become available to an already configured tracer"
    features.discover()

    then: "metrics endpoint not probed, metrics and dropping enabled"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    features.supportsMetrics()
    (features as DroppingPolicy).active()
    features.state() == INFO_WITH_CLIENT_DROPPING_STATE
    0 * _
  }

  def "disappearance of info endpoint after agent downgrade disables metrics and dropping"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true)

    when: "/info available"
    features.discover()

    then: "metrics and dropping supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute(_)
    features.supportsMetrics()
    (features as DroppingPolicy).active()
    features.state() == INFO_WITH_CLIENT_DROPPING_STATE

    when: "/info and v0.6/stats become unavailable to an already configured tracer"
    features.discover()

    then: "metrics and dropping not supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> notFound(request) }
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute(_)
    !features.supportsMetrics()
    !(features as DroppingPolicy).active()
    features.state() == PROBE_STATE
    0 * _
  }

  def "disappearance of metrics endpoint after agent downgrade disables metrics and dropping"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true)

    when: "/info available"
    features.discover()

    then: "metrics and dropping supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    0 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { HttpRequest request -> success(request) }
    0 * client.execute(_)
    features.supportsMetrics()
    (features as DroppingPolicy).active()
    features.state() == INFO_WITH_CLIENT_DROPPING_STATE

    when: "/info and v0.6/stats become unavailable to an already configured tracer"
    features.discover()

    then: "metrics and dropping not supported"
    1 * client.execute({ HttpRequest request -> request.url().toString() == "http://localhost:8125/info" }) >> { HttpRequest request -> infoResponse(request, INFO_WITHOUT_METRICS_RESPONSE) }
    0 * client.execute(_)
    !features.supportsMetrics()
    // but we don't permit dropping anyway
    !(features as DroppingPolicy).active()
    features.state() == INFO_WITHOUT_METRICS_STATE
    !features.supportsTelemetryProxy()
    0 * _
  }

  def "test parse /info response with telemetry proxy"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_WITH_TELEMETRY_PROXY_RESPONSE) }
    features.supportsTelemetryProxy()
    features.supportsDebugger()
    features.getDebuggerSnapshotEndpoint() == "debugger/v1/diagnostics"
    features.supportsDebuggerDiagnostics()
    0 * _
  }

  def "test parse /info response with old EVP proxy"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_WITH_OLD_EVP_PROXY) }
    features.supportsEvpProxy()
    features.getEvpProxyEndpoint() == "evp_proxy/v2/" // v3 is advertised, but the tracer should ignore it
    !features.supportsContentEncodingHeadersWithEvpProxy()
    features.supportsDebugger()
    features.getDebuggerSnapshotEndpoint() == "debugger/v1/diagnostics"
    features.supportsDebuggerDiagnostics()
    0 * _
  }

  def "test parse /info response with peer tag back propagation"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_RESPONSE) }

    when: "/info available with peer tag back propagation"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request -> infoResponse(request, INFO_WITH_PEER_TAG_BACK_PROPAGATION_RESPONSE) }
    features.state() == INFO_WITH_PEER_TAG_BACK_PROPAGATION_STATE
    features.peerTags().containsAll(
    "_dd.base_service",
    "active_record.db.vendor",
    "amqp.destination",
    "amqp.exchange",
    "amqp.queue",
    "grpc.host",
    "hostname",
    "http.host",
    "http.server_name",
    "streamname",
    "tablename",
    "topicname"
    )
  }

  def "test metrics disabled for agent version below 7.65"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "agent version is below 7.65"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request ->
      def response = """
      {
        "version": "${version}",
        "endpoints": ["/v0.5/traces", "/v0.6/stats"],
        "client_drop_p0s": true,
        "config": {}
      }
      """
      infoResponse(request, response)
    }
    features.getMetricsEndpoint() == V06_METRICS_ENDPOINT
    features.supportsMetrics() == expected

    where:
    version       | expected
    "7.64.0"      | false
    "7.64.9"      | false
    "7.64.9-rc.1" | false
    "7.65.0"      | true
    "7.65.1"      | true
    "7.70.0"      | true
    "8.0.0"       | true
  }

  def "test metrics disabled for agent with unparseable version"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "agent version is unparseable"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request ->
      def response = """
      {
        "version": "${version}",
        "endpoints": ["/v0.5/traces", "/v0.6/stats"],
        "client_drop_p0s": true,
        "config": {}
      }
      """
      infoResponse(request, response)
    }
    !features.supportsMetrics()

    where:
    version << ["invalid", "7", "7.65", "", null]
  }

  def "should send container id as header on the info request and parse the hash in the response"() {
    setup:
    HttpClient client = Mock(HttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)
    def oldContainerId = ContainerInfo.get().getContainerId()
    def oldContainerTagsHash = ContainerInfo.get().getContainerTagsHash()
    ContainerInfo.get().setContainerId("test")

    when: "/info requested"
    features.discover()

    then:
    1 * client.execute(_) >> { HttpRequest request ->
      assert request.header(DATADOG_CONTAINER_ID) == "test"
      infoResponse(request, INFO_RESPONSE, Headers.of(DATADOG_CONTAINER_TAGS_HASH, "test-hash"))
    }
    and:
    assert ContainerInfo.get().getContainerTagsHash() == "test-hash"
    cleanup:
    ContainerInfo.get().setContainerId(oldContainerId)
    ContainerInfo.get().setContainerTagsHash(oldContainerTagsHash)
  }

  def infoResponse(HttpRequest request, String json, Headers headers = new Headers.Builder().build()) {
    return OkHttpResponse.wrap(new Response.Builder()
    .code(200)
    .request(mockOkHttpRequest(request))
    .protocol(Protocol.HTTP_1_1)
    .message("")
    .headers(headers)
    .body(ResponseBody.create(MediaType.get("application/json"), json))
    .build())
  }

  def notFound(HttpRequest request) {
    return OkHttpResponse.wrap(new Response.Builder()
    .code(404)
    .request(mockOkHttpRequest(request))
    .protocol(Protocol.HTTP_1_1)
    .message("")
    .header(DDAgentFeaturesDiscovery.DATADOG_AGENT_STATE, PROBE_STATE)
    .body(ResponseBody.create(MediaType.get("application/json"), ""))
    .build())
  }

  def clientError(HttpRequest request) {
    return OkHttpResponse.wrap(new Response.Builder()
    .code(400)
    .request(mockOkHttpRequest(request))
    .protocol(Protocol.HTTP_1_1)
    .message("")
    .header(DDAgentFeaturesDiscovery.DATADOG_AGENT_STATE, PROBE_STATE)
    .body(ResponseBody.create(MediaType.get("application/msgpack"), ""))
    .build())
  }

  def success(HttpRequest request) {
    return OkHttpResponse.wrap(new Response.Builder()
    .code(200)
    .request(mockOkHttpRequest(request))
    .protocol(Protocol.HTTP_1_1)
    .message("")
    .header(DDAgentFeaturesDiscovery.DATADOG_AGENT_STATE, PROBE_STATE)
    .body(ResponseBody.create(MediaType.get("application/msgpack"), ""))
    .build())
  }

  private Request mockOkHttpRequest(HttpRequest request) {
    return new Request.Builder()
    .url(request.url().toString())
    .build()
  }

  private static String loadJsonFile(String name) {
    return new String(Files.readAllBytes(Paths.get("src/test/resources/agent-features").resolve(name)))
  }
}
