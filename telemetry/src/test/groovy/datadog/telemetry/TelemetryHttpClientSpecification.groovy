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
}
