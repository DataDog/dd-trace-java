package datadog.telemetry

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.telemetry.api.RequestType
import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.Strings
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

class TelemetryHttpClientSpecification extends DDSpecification {

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

  OkHttpClient okHttpClient = Mock()
  DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = Mock()

  def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

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

  def 'keep trying to send telemetry to Agent despite of return code when API_KEY'() {
    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >>> [true, false]
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> [false, true]
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null

    where:
    returnCode | _
    200        | _
    404        | _
    500        | _
  }

  def 'send telemetry to Intake when Agent does not support telemetry endpoint and only when API_KEY is set'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "api-key-value")
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"
    request.header("DD-API-KEY") == "api-key-value"

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == expectedUrl
    request.header("DD-API-KEY") == expectedApiKey

    where:
    returnCode | expectedUrl                                               | expectedApiKey
    200        | "https://instrumentation-telemetry-intake.datadoghq.com/" | "api-key-value"
    404        | "https://example.com/telemetry/proxy/api/v2/apmtelemetry" | null
    500        | "https://example.com/telemetry/proxy/api/v2/apmtelemetry" | null
  }

  def 'switch to Intake when Agent stops supporting telemetry proxy and telemetry requests start failing'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "api-key-value")
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"
    request.header("DD-API-KEY") == "api-key-value"

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'do not switch to Intake when Agent stops supporting telemetry proxy but accepts telemetry requests'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "api-key-value")
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(200) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(201) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null
  }

  def 'switch to Intake when Agent fails to receive telemetry request'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "api-key-value")
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"
    request.header("DD-API-KEY") == "api-key-value"

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'switch to Agent once it becomes available and Intake still accepts requests'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "api-key-value")
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"
    request.header("DD-API-KEY") == "api-key-value"

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"
    request.header("DD-API-KEY") == "api-key-value"

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null

    where:
    returnCode | _
    200        | _
    201        | _
  }

  def 'switch between Agent and Intake (only if an api key is set) when either one fails on a telemetry request'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), apiKey)
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == expectedUrl
    request.header("DD-API-KEY") == apiKey

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    request.header("DD-API-KEY") == null

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == expectedUrl
    request.header("DD-API-KEY") == apiKey

    where:
    returnCode | apiKey          | expectedUrl
    404        | null            | "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    500        | null            | "https://example.com/telemetry/proxy/api/v2/apmtelemetry"
    404        | "api-key-value" | "https://instrumentation-telemetry-intake.datadoghq.com/"
    500        | "api-key-value" | "https://instrumentation-telemetry-intake.datadoghq.com/"
  }
}
