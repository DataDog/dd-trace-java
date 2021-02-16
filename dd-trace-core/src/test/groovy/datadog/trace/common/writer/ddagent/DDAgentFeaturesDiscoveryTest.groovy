package datadog.trace.common.writer.ddagent

import com.timgroup.statsd.NoOpStatsDClient
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
import java.util.concurrent.TimeUnit

class DDAgentFeaturesDiscoveryTest extends DDSpecification {

  @Shared
  Monitoring monitoring = new Monitoring(new NoOpStatsDClient(), 1, TimeUnit.SECONDS)

  @Shared
  HttpUrl agentUrl = HttpUrl.get("http://localhost:8125")

  static final String INFO_RESPONSE = loadJsonFile("agent-info.json")
  static final String INFO_WITH_CLIENT_DROPPING_RESPONSE = loadJsonFile("agent-info-with-client-dropping.json")

  def "test parse /info response"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring,agentUrl, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.newCall(_) >> { Request request -> infoResponse(request, INFO_RESPONSE) }
    features.getMetricsEndpoint() == "v0.5/stats"
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    !features.supportsDropping()
  }

  def "test parse /info response with client dropping"() {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true)

    when: "/info available"
    features.discover()

    then:
    1 * client.newCall(_) >> { Request request -> infoResponse(request, INFO_WITH_CLIENT_DROPPING_RESPONSE) }
    features.getMetricsEndpoint() == "v0.5/stats"
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    features.supportsDropping()
  }

  def "test fallback when /info not found" () {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/stats" }) >> { Request request -> clientError(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == "v0.5/stats"
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    !features.supportsDropping()
  }

  def "test fallback when /info not found and agent returns ok" () {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/stats" }) >> { Request request -> success(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> success(request) }
    features.getMetricsEndpoint() == "v0.5/stats"
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.5/traces"
    !features.supportsDropping()
  }

  def "test fallback when /info not found and v0.5 disabled" () {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/stats" }) >> { Request request -> clientError(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == "v0.5/stats"
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    !features.supportsDropping()
  }

  def "test fallback when /info not found and v0.5 unavailable agent side" () {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/stats" }) >> { Request request -> clientError(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == "v0.5/stats"
    features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    !features.supportsDropping()
  }

  def "test fallback on old agent" () {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring,agentUrl, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/stats" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.4/traces"
    !features.supportsDropping()
  }

  def "test fallback on very old agent" () {
    setup:
    OkHttpClient client = Mock(OkHttpClient)
    DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(client, monitoring,agentUrl, true)

    when: "/info unavailable"
    features.discover()

    then:
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/info" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/stats" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.5/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.4/traces" }) >> { Request request -> notFound(request) }
    1 * client.newCall({ Request request -> request.url().toString() == "http://localhost:8125/v0.3/traces" }) >> { Request request -> clientError(request) }
    features.getMetricsEndpoint() == null
    !features.supportsMetrics()
    features.getTraceEndpoint() == "v0.3/traces"
    !features.supportsDropping()
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
