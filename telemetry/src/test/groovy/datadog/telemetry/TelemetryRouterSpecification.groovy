package datadog.telemetry

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.HttpRetryPolicy
import datadog.http.client.HttpClient
import datadog.http.client.HttpRequest
import datadog.http.client.HttpResponse
import datadog.http.client.HttpUrl
import datadog.telemetry.api.RequestType
import spock.lang.Specification

class TelemetryRouterSpecification extends Specification {

  def dummyRequest() {
    return new TelemetryRequest(Mock(EventSource), Mock(EventSink), 1000, RequestType.APP_STARTED, false)
  }

  HttpResponse mockResponse(int statusCode) {
    Stub(HttpResponse) {
      code() >> statusCode
      isSuccessful() >> (statusCode >= 200 && statusCode < 300)
      bodyAsString() >> "OK"
      close() >> {}
    }
  }

  static HttpUrl agentUrl = HttpUrl.parse("https://agent.example.com")
  static HttpUrl agentTelemetryUrl = agentUrl.newBuilder().addPathSegment("telemetry/proxy/api/v2/apmtelemetry").build()
  static HttpUrl intakeUrl = HttpUrl.parse("https://intake.example.com")
  static String apiKey = "api-key"
  static String apiKeyHeader = "DD-API-KEY"

  HttpClient httpClient = Mock()
  DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = Mock()

  def agentTelemetryClient = TelemetryClient.buildAgentClient(httpClient, agentUrl, HttpRetryPolicy.Factory.NEVER_RETRY)
  def intakeTelemetryClient = new TelemetryClient(httpClient, HttpRetryPolicy.Factory.NEVER_RETRY, intakeUrl, apiKey)
  def telemetryRouter = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, false)

  def 'map an http status code to the correct send result'() {
    setup:
    def response = mockResponse(httpCode)

    when:
    def result = telemetryRouter.sendRequest(dummyRequest())

    then:
    result == sendResult
    1 * httpClient.execute(_) >> response

    where:
    httpCode | sendResult
    100      | TelemetryClient.Result.FAILURE
    202      | TelemetryClient.Result.SUCCESS
    404      | TelemetryClient.Result.NOT_FOUND
    500      | TelemetryClient.Result.FAILURE
  }

  def 'catch IOException from HttpClient and return FAILURE'() {
    when:
    def result = telemetryRouter.sendRequest(dummyRequest())

    then:
    result == TelemetryClient.Result.FAILURE
    1 * httpClient.execute(_) >> { throw new IOException("exception") }
  }

  def 'catch InterruptedIOException from HttpClient and return INTERRUPTED'() {
    when:
    def result = telemetryRouter.sendRequest(dummyRequest())

    then:
    result == TelemetryClient.Result.INTERRUPTED
    1 * httpClient.execute(_) >> { throw new InterruptedIOException("interrupted") }
  }

  def 'keep trying to send telemetry to Agent despite of return code when Intake client is null'() {
    setup:
    def router = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, null, false)

    HttpRequest request

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >>> [true, false]
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> [false, true]
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    where:
    returnCode | _
    200        | _
    404        | _
    500        | _
  }

  def 'switch to Intake when Agent stops supporting telemetry proxy and telemetry requests start failing'() {
    HttpRequest request

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'when configured to prefer Intake: use Intake client from the start'() {
    HttpRequest request

    setup:
    def router = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true)

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(200) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey
  }

  def 'when configured to prefer Intake: do not switch to Agent if Intake request succeeds, even if Agent supports telemetry proxy'() {
    HttpRequest request

    setup:
    def router = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true)

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(200) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(200) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey
  }

  def 'when configured to prefer Intake: do not switch to Agent if request is interrupted'() {
    HttpRequest request

    setup:
    def router = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true)

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * httpClient.execute(_) >> { args -> request = args[0]; throw new InterruptedIOException("interrupted") }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(200) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey
  }

  def 'when configured to prefer Intake: switch to Agent if Intake request fails'() {
    HttpRequest request

    setup:
    def router = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true)

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(403) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(200) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null
  }

  def 'do not switch to Intake when Agent stops supporting telemetry proxy but accepts telemetry requests'() {
    HttpRequest request

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(200) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(201) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null
  }

  def 'switch to Intake when Agent fails to receive telemetry requests'() {
    HttpRequest request

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >>> [true, false]
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >>> [false, true]
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'use Agent when Intake is not available'() {
    setup:
    def router = new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, null, false)

    HttpRequest request

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args ->
      request = args[0]; mockResponse(returnCode)
    }
    request.url().toString() == expectedUrl.toString()
    request.header(apiKeyHeader) == expectedApiKey

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    when:
    router.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == expectedUrl.toString()
    request.header(apiKeyHeader) == expectedApiKey

    where:
    returnCode | expectedApiKey | expectedUrl
    404        | null           | agentTelemetryUrl
    500        | null           | agentTelemetryUrl
  }

  def 'switch to Intake then back to Agent when both fail to receive telemetry requests'() {
    HttpRequest request

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then: 'always send first telemetry request to Agent'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args ->
      request = args[0]; mockResponse(returnCode)
    }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then: 'switch to Intake if sending a telemetry request to Agent failed or Agent supports telemetry proxy'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then: 'switch back to Agent if Intake request fails'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'switch back to Agent if it starts supporting telemetry'() {
    HttpRequest request

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then: 'always send first telemetry request to Agent'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args ->
      request = args[0]; mockResponse(returnCode)
    }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then: 'switch to Intake if sending a telemetry request to Agent failed or Agent supports telemetry proxy'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(201) }
    request.url().toString() == intakeUrl.toString()
    request.header(apiKeyHeader) == apiKey

    when:
    telemetryRouter.sendRequest(dummyRequest())

    then: 'switch back to Agent if it starts supporting telemetry proxy'
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * httpClient.execute(_) >> { args -> request = args[0]; mockResponse(returnCode) }
    request.url().toString() == agentTelemetryUrl.toString()
    request.header(apiKeyHeader) == null

    where:
    returnCode | _
    404        | _
    500        | _
  }
}
