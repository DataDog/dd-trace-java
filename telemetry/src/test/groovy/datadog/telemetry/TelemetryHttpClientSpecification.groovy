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

class TelemetryHttpClientSpecification extends Specification {

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

  def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, apiKey)

  def 'map an http status code to the correct send result'() {
    when:
    def result = httpClient.sendRequest(dummyRequest())

    then:
    result == sendResult
    1 * okHttpClient.newCall(_) >> mockResponse(httpCode)

    where:
    httpCode | sendResult
    100      | TelemetryHttpClient.Result.FAILURE
    202      | TelemetryHttpClient.Result.SUCCESS
    404      | TelemetryHttpClient.Result.NOT_FOUND
    500      | TelemetryHttpClient.Result.FAILURE
  }

  def 'catch IOException from OkHttpClient and return FAILURE'() {
    when:
    def result = httpClient.sendRequest(dummyRequest())

    then:
    result == TelemetryHttpClient.Result.FAILURE
    1 * okHttpClient.newCall(_) >> { throw new IOException("exception") }
  }

  def 'keep trying to send telemetry to Agent despite of return code when API_KEY unset'() {
    setup:
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, null)

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

  def 'send telemetry to Intake when Agent does not support telemetry endpoint and only when API_KEY set'() {
    setup:
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, apiKey)

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == expectedUrl
    request.header(apiKeyHeader) == expectedApiKey

    where:
    returnCode | expectedUrl       | expectedApiKey
    200        | intakeUrl         | apiKey
    404        | agentTelemetryUrl | null
    500        | agentTelemetryUrl | null
  }

  def 'switch to Intake when Agent stops supporting telemetry proxy and telemetry requests start failing'() {
    setup:
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, apiKey)

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
    setup:
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, apiKey)

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

  def 'switch to Intake when Agent fails to receive telemetry request'() {
    setup:
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, apiKey)

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
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'switch to Agent once it becomes available and Intake still accepts requests'() {
    setup:
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, apiKey)

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == intakeUrl
    request.header(apiKeyHeader) == apiKey

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == agentTelemetryUrl
    request.header(apiKeyHeader) == null

    where:
    returnCode | _
    200        | _
    201        | _
  }

  def 'switch between Agent and Intake (only if an api key is set) when either one fails on a telemetry request'() {
    setup:
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, agentUrl, intakeUrl, apiKey)

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url() == expectedUrl
    request.header(apiKeyHeader) == apiKey

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
    request.header(apiKeyHeader) == apiKey

    where:
    returnCode | apiKey | expectedUrl
    404        | null   | agentTelemetryUrl
    500        | null   | agentTelemetryUrl
    404        | apiKey | intakeUrl
    500        | apiKey | intakeUrl
  }
}
