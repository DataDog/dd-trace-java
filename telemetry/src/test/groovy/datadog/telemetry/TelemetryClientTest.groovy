package datadog.telemetry

import datadog.communication.http.HttpRetryPolicy
import datadog.communication.http.OkHttpUtils
import datadog.telemetry.api.RequestType
import datadog.trace.api.Config
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class TelemetryClientTest extends Specification {

  def "Intake client uses correct URL for site #site"() {
    setup:
    def config = Stub(Config)
    config.getApiKey() >> "dummy-key"
    config.getAgentTimeout() >> 123
    config.getSite() >> site

    long timeoutMillis = TimeUnit.SECONDS.toMillis(config.getAgentTimeout())
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(false, null, null, timeoutMillis)

    when:
    def intakeClient = TelemetryClient.buildIntakeClient(config, httpClient, HttpRetryPolicy.Factory.NEVER_RETRY)

    then:
    intakeClient.getUrl().toString() == expectedUrl

    where:
    site                | expectedUrl
    "datadoghq.com"     | "https://instrumentation-telemetry-intake.datadoghq.com/api/v2/apmtelemetry"
    "us3.datadoghq.com" | "https://instrumentation-telemetry-intake.us3.datadoghq.com/api/v2/apmtelemetry"
    "us5.datadoghq.com" | "https://instrumentation-telemetry-intake.us5.datadoghq.com/api/v2/apmtelemetry"
    "ap1.datadoghq.com" | "https://instrumentation-telemetry-intake.ap1.datadoghq.com/api/v2/apmtelemetry"
    "datadoghq.eu"      | "https://instrumentation-telemetry-intake.datadoghq.eu/api/v2/apmtelemetry"
    "datad0g.com"       | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
  }

  def "Intake client uses CI Visibility agentless URL if configured to do so"() {
    setup:
    def config = Stub(Config)
    config.getApiKey() >> "dummy-key"
    config.getAgentTimeout() >> 123
    config.getSite() >> "datad0g.com"
    config.isCiVisibilityEnabled() >> ciVisEnabled
    config.isCiVisibilityAgentlessEnabled() >> ciVisAgentlessEnabled
    config.getCiVisibilityAgentlessUrl() >> ciVisAgentlessUrl

    long timeoutMillis = TimeUnit.SECONDS.toMillis(config.getAgentTimeout())
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(false, null, null, timeoutMillis)

    when:
    def intakeClient = TelemetryClient.buildIntakeClient(config, httpClient, HttpRetryPolicy.Factory.NEVER_RETRY)

    then:
    intakeClient.getUrl().toString() == expectedUrl

    where:
    ciVisEnabled | ciVisAgentlessEnabled | ciVisAgentlessUrl                    | expectedUrl
    true         | true                  | "http://ci.visibility.agentless.url" | "http://ci.visibility.agentless.url/api/v2/apmtelemetry"
    false        | true                  | "http://ci.visibility.agentless.url" | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
    true         | false                 | "http://ci.visibility.agentless.url" | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
    true         | true                  | null                                 | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
  }

  def "Intake client retries telemetry request if configured to do so"() {
    setup:
    def httpClient = Mock(OkHttpClient)
    def httpRetryPolicy = new HttpRetryPolicy.Factory(2, 50, 1.5, true)
    def httpUrl = HttpUrl.get("https://intake.example.com")
    def intakeClient =  new TelemetryClient(httpClient, httpRetryPolicy, httpUrl, "dummy-api-key")

    when:
    intakeClient.sendHttpRequest(dummyRequest())

    then:
    // original request + 2 retries
    3 * httpClient.newCall(_) >> { throw new ConnectException("exception") }
  }

  def dummyRequest() {
    return new TelemetryRequest(Mock(EventSource), Mock(EventSink), 1000, RequestType.APP_STARTED, false).httpRequest()
  }
}
