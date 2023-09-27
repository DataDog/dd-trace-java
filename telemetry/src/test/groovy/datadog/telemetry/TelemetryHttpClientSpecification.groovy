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

  def 'send telemetry to Intake when Agent does not support telemetry endpoint and API_KEY set'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "apk-key-value")
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"
    request.header("DD-API-KEY") == "apk-key-value"

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"
    request.header("DD-API-KEY") == "apk-key-value"

    where:
    returnCode | _
    200        | _
    404        | _
    500        | _
  }

  def 'switch to Intake when Agent stops supporting telemetry proxy and when telemetry requests fail'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "apk-key-value")
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
    request.header("DD-API-KEY") == "apk-key-value"

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'do not switch to Intake when Agent stops supporting telemetry proxy but accepts telemetry requests'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "apk-key-value")
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
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "apk-key-value")
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
    request.header("DD-API-KEY") == "apk-key-value"

    where:
    returnCode | _
    404        | _
    500        | _
  }

  def 'switch to Agent once it becomes available'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "apk-key-value")
    def httpClient = new TelemetryHttpClient(ddAgentFeaturesDiscovery, okHttpClient, HttpUrl.get("https://example.com"))

    Request request

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> true
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://instrumentation-telemetry-intake.datadoghq.com/"

    when:
    httpClient.sendRequest(dummyRequest())

    then:
    1 * ddAgentFeaturesDiscovery.discoverIfOutdated()
    1 * ddAgentFeaturesDiscovery.supportsTelemetryProxy() >> false
    1 * okHttpClient.newCall(_) >> { args -> request=args[0]; mockResponse(returnCode) }
    request.url().toString() == "https://example.com/telemetry/proxy/api/v2/apmtelemetry"

    where:
    returnCode | _
    200        | _
    404        | _
    500        | _
  }
}
