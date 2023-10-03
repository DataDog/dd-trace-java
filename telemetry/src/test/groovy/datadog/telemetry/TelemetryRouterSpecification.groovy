package datadog.telemetry

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.telemetry.api.RequestType
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import spock.lang.Specification

class TelemetryRouterSpecification extends Specification {

  def dummyRequest() {
    return new TelemetryRequest(Mock(EventSource), Mock(EventSink), 1000, RequestType.APP_STARTED, false)
  }

  Call mockResponse(int code) {
    Stub(Call) {
      execute() >> {
        new Response.Builder()
          .request(new Request.Builder().url(HttpUrl.get("https://example.com")).build())
          .protocol(Protocol.HTTP_1_1)
          .message("OK")
          .body(ResponseBody.create(MediaType.get("text/plain"), "OK"))
          .code(code)
          .build()
      }
    }
  }

  static HttpUrl agentUrl = HttpUrl.get("https://agent.example.com")
  static HttpUrl agentTelemetryUrl = agentUrl.resolve("telemetry/proxy/api/v2/apmtelemetry")
  static HttpUrl intakeUrl = HttpUrl.get("https://intake.example.com")
  static String apiKey = "api-key"
  static String apiKeyHeader = "DD-API-KEY"

  OkHttpClient okHttpClient = Mock()
  DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = Mock()

  def agentTelemetryClient = TelemetryClient.buildAgentClient(okHttpClient, agentUrl)
  def intakeTelemetryClient = new TelemetryClient(okHttpClient, intakeUrl, apiKey)
  def httpClient = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient)

  def 'map an http status code to the correct send result'() {
    when:
    def result = httpClient.sendRequest(dummyRequest())

    then:
    result == sendResult
    1 * okHttpClient.newCall(_) >> mockResponse(httpCode)

    where:
    httpCode | sendResult
    100      | TelemetryClient.Result.FAILURE
    202      | TelemetryClient.Result.SUCCESS
    404      | TelemetryClient.Result.NOT_FOUND
    500      | TelemetryClient.Result.FAILURE
  }

  def 'catch IOException from OkHttpClient and return FAILURE'() {
    when:
    def result = httpClient.sendRequest(dummyRequest())

    then:
    result == TelemetryClient.Result.FAILURE
    1 * okHttpClient.newCall(_) >> { throw new IOException("exception") }
  }

  def 'keep trying to send telemetry to Agent despite of return code when Intake client is null'() {
    setup:
    def httpClient = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, null)

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >>> [true, false]
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> [false, true]
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    where:
    returnCode | _
    200        | _
    404        | _
    500        | _
  }

  def 'switch to Intake when Agent stops supporting telemetry proxy and telemetry requests start failing'() {
    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'do not switch to Intake when Agent stops supporting telemetry proxy but accepts telemetry requests'() {
    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(200) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(201) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null
  }

  def 'switch to Intake when Agent fails to receive telemetry requests'() {
    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >>> [true, false]
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >>> [false, true]
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'use Agent when Intake is not available'() {
    setup:
    def httpClient = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, null)

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args ->
      request = args[0]; mockResponse(returnCode)
    }
    request.url() == expectedUrl
    request.header(apiKeyHeader) == expectedApiKey

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == expectedUrl
    request.header(apiKeyHeader) == expectedApiKey

    where:
    returnCode | expectedApiKey | expectedUrl
    404        | null           | agentTelemetryUrl
    500        | null           | agentTelemetryUrl
  }

  def 'switch to Intake when Agent fails to receive telemetry requests'() {
    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then: 'always send first telemetry request to Agent'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args ->
      request = args[0]; mockResponse(returnCode)
    }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    when:
    httpClient.sendRequest(dummyRequest())

    then: 'switch to Intake if sending a telemetry request to Agent failed or Agent supports telemetry proxy'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    when:
    httpClient.sendRequest(dummyRequest())

    then: 'switch back to Agent if Intake request fails'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'switch back to Agent if it starts supporting telemetry'() {
    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then: 'always send first telemetry request to Agent'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args ->
      request = args[0]; mockResponse(returnCode)
    }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    when:
    httpClient.sendRequest(dummyRequest())

    then: 'switch to Intake if sending a telemetry request to Agent failed or Agent supports telemetry proxy'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(201) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    when:
    httpClient.sendRequest(dummyRequest())

    then: 'switch back to Agent if it starts supporting telemetry proxy'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    where:
    returnCode | _
    404        | _
    500        | _
  }
}
