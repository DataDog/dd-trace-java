package datadog.trace.common.writer.ddagent

import datadog.trace.api.StatsDClient
import datadog.trace.core.monitor.Monitoring
import datadog.trace.test.util.DDSpecification
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import spock.lang.Shared

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.common.writer.ddagent.DDAgentFeaturesDiscovery.V6_METRICS_ENDPOINT

class DDAgentFeaturesDiscoveryTest extends DDSpecification {

  @Shared
  Monitoring monitoring = new Monitoring(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)

  @Shared
  HttpUrl agentUrl = HttpUrl.get("http://localhost:8125")

  static final String INFO_RESPONSE = loadJsonFile("agent-info.json")
  static final String INFO_WITH_CLIENT_DROPPING_RESPONSE = loadJsonFile("agent-info-with-client-dropping.json")

  def "test parse /info response"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.newCall(_) >> { Request request -> infoResponse(request, INFO_RESPONSE) }
    features.getMetricsEndpoint() == V6_METRICS_ENDPOINT
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    !features.supportsDropping()
  }

  def "test parse /info response with client dropping"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.newCall(_) >> { Request request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    features.getMetricsEndpoint() == V6_METRICS_ENDPOINT
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    features.supportsDropping()
  }

  def "test fallback when /info not found"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { Request request -> clientError(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    !features.supportsDropping()
  }

  def "test fallback when /info not found and agent returns ok"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { Request request -> success(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> success(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    !features.supportsDropping()
  }

  def "test fallback when /info not found and v0.5 disabled"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { Request request -> clientError(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    !features.supportsDropping()
  }

  def "test fallback when /info not found and v0.5 unavailable agent side"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { Request request -> clientError(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    !features.supportsDropping()
  }

  def "test fallback on old agent"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    !features.supportsDropping()
  }

  def "test fallback on very old agent"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.3/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.3/traces"
    !features.supportsDropping()
  }

  def "disabling metrics disables metrics and dropping"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, false)

    when: "/info unavailable"
    features.discover()

    then: "metrics endpoint not probed, metrics and dropping not supported"
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> success(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" })
    !features.supportsMetrics()
    !features.supportsDropping()
    !(features as DroppingPolicy).active()

    when: "/info available and agent allows dropping"
    features.discover()

    then: "metrics and dropping not supported"
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    !features.supportsMetrics()
    !features.supportsDropping()
    !(features as DroppingPolicy).active()

    when: "/info available and agent does not allow dropping"
    features.discover()

    then: "metrics and dropping not supported"
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> infoResponse(request, INFO_RESPONSE) }
    !features.supportsMetrics()
    !features.supportsDropping()
    !(features as DroppingPolicy).active()
  }

  def "discovery of metrics endpoint after agent upgrade does not enable dropping"() {
    // discovery of the metrics endpoint would lead to code being deoptimised on the application threads
    // so the user needs to restart applications to start producing metrics after the agent upgrade
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true)

    when: "/info unavailable"
    features.discover()

    then: "metrics endpoint not probed, metrics and dropping not supported"
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> success(request) }
    0 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.6/stats" })
    !features.supportsDropping()
    !features.supportsMetrics()
    !(features as DroppingPolicy).active()

    when: "/info and v0.6/stats become available to an already configured tracer"
    features.discover()

    then: "metrics endpoint not probed, metrics and dropping not supported"
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    features.supportsDropping()
    !features.supportsMetrics()
    !(features as DroppingPolicy).active()
  }

  def countingNotFound(Request request, CountDownLatch latch) {
    latch.countDown()
    return notFound(request)
  }

  def countingInfoResponse(Request request, String json, CountDownLatch latch) {
    latch.countDown()
    return infoResponse(request, json)
  }

  def infoResponse(Request request, String json) {
    return Mock(Call) {
      it.execute() >> new Response.Builder()
        .code(200)
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .message("")
        .body(ResponseBody.create(MediaType.get("application/json"), json))
        .build()
    }
  }

  def notFound(Request request) {
    return Mock(Call) {
      it.execute() >> new Response.Builder()
        .code(404)
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .message("")
        .body(ResponseBody.create(MediaType.get("application/json"), ""))
        .build()
    }
  }

  def clientError(Request request) {
    return Mock(Call) {
      it.execute() >> new Response.Builder()
        .code(400)
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .message("")
        .body(ResponseBody.create(MediaType.get("application/msgpack"), ""))
        .build()
    }
  }

  def success(Request request) {
    return Mock(Call) {
      it.execute() >> new Response.Builder()
        .code(200)
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .message("")
        .body(ResponseBody.create(MediaType.get("application/msgpack"), ""))
        .build()
    }
  }

  private static String loadJsonFile(String name) {
    return new String(Files.readAllBytes(Paths.get("src/test/resources/agent-features").resolve(name)))
  }
}
