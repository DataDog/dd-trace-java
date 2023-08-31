package datadog.telemetry

import datadog.telemetry.dependency.Dependency
import datadog.telemetry.api.Integration
import datadog.telemetry.api.DistributionSeries

import datadog.telemetry.api.ConfigChange
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.LogMessageLevel
import datadog.telemetry.api.Metric
import datadog.telemetry.api.RequestType
import okhttp3.HttpUrl
import spock.lang.Specification

class TelemetryServiceSpecification extends Specification {

  def configuration = ["confkey": "confvalue"]
  def confKeyValue = new ConfigChange("confkey", "confvalue")
  def integration = new Integration("integration", true)
  def dependency = new Dependency("dependency", "1.0.0", "src", "hash")
  def metric = new Metric().namespace("tracers").metric("metric").points([[1, 2]]).tags(["tag1", "tag2"])
  def distribution = new DistributionSeries().namespace("tracers").metric("distro").points([1, 2, 3]).tags(["tag1", "tag2"]).common(false)
  def logMessage = new LogMessage().message("log-message").tags("tag1:tag2").level(LogMessageLevel.DEBUG).stackTrace("stack-trace").tracerTime(32423)

  void 'happy path without data'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 10000)

    when: 'first iteration'
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'app-started'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products()
    testHttpClient.assertNoMoreRequests()

    when: 'second iteration'
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'app-heartbeat only'
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT)
    testHttpClient.assertNoMoreRequests()

    when: 'third iteration'
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'app-heartbeat only'
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT)
    testHttpClient.assertNoMoreRequests()
  }

  void 'happy path with data'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 10000)

    when: 'add data before first iteration'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and: 'send messages'
    testHttpClient.expectRequests(2, HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload()
      .products()
      .configuration([confKeyValue])
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(6)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      // no configuration here as it has already been sent with the app-started event
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()

    when: 'second iteration heartbeat only'
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).assertNoPayload()
    testHttpClient.assertNoMoreRequests()

    when: 'third iteration metrics data'
    telemetryService.addMetric(metric)
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(2)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()
  }

  void 'happy path with data after app-started'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 10000)

    when: 'send messages'
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products()
    testHttpClient.assertNoMoreRequests()

    when: 'add data after first iteration'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and: 'send messages'
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(7)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      .assertNextMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE).hasPayload().configuration([confKeyValue])
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()
  }

  void 'no message before app-started'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 10000)

    when: 'attempt with 404 error'
    testHttpClient.expectRequest(HttpClient.Result.NOT_FOUND)
    telemetryService.sendTelemetryEvents()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products()
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with 500 error'
    testHttpClient.expectRequest(HttpClient.Result.FAILURE)
    telemetryService.sendTelemetryEvents()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products()
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with unexpected FAILURE (not valid)'
    testHttpClient.expectRequest(HttpClient.Result.FAILURE)
    telemetryService.sendTelemetryEvents()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products()
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with success'
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products()
    testHttpClient.assertNoMoreRequests()
  }

  def 'resend data on successful attempt after a failure'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 10000)

    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    when: 'attempt with NOT_FOUND error'
    testHttpClient.expectRequest(HttpClient.Result.NOT_FOUND)
    telemetryService.sendTelemetryEvents()

    then: 'app-started attempted with config'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().configuration([confKeyValue])
    testHttpClient.assertNoMoreRequests()

    when: 'successful attempt'
    testHttpClient.expectRequests(2, HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'attempt with SUCCESS'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().configuration([confKeyValue])
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(6)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      // no configuration here as it has already been sent with the app-started event
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with NOT_FOUND error'
    testHttpClient.expectRequest(HttpClient.Result.FAILURE)
    telemetryService.sendTelemetryEvents()

    then: 'message-batch attempted with heartbeat'
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).assertNoPayload()
    testHttpClient.assertNoMoreRequests()
  }

  void 'Send closing event request'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 10000)

    when:
    testHttpClient.expectRequest(HttpClient.Result.SUCCESS)
    telemetryService.sendAppClosingEvent()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_CLOSING)
    testHttpClient.assertNoMoreRequests()
  }

  void 'report when both OTel and OT are enabled'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = Spy(new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 1000))
    def otel = new Integration("opentelemetry-1", otelEnabled)
    def ot = new Integration("opentracing", otEnabled)

    when:
    telemetryService.addIntegration(otel)

    then:
    0 * telemetryService.warnAboutExclusiveIntegrations()

    when:
    telemetryService.addIntegration(ot)

    then:
    warnining * telemetryService.warnAboutExclusiveIntegrations()

    where:
    otelEnabled | otEnabled | warnining
    true        | true      | 1
    true        | false     | 0
    false       | true      | 0
    false       | false     | 0
  }

  void 'split telemetry requests if the size above the limit'() {
    setup:
    TestHttpClient testHttpClient = new TestHttpClient()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"), 1000)

    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    when: 'successful attempt'
    testHttpClient.expectRequests(3, HttpClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'attempt with SUCCESS'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().configuration([confKeyValue])
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(4)
      //TODO should a heartbeat be included in the batch request?
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      // no configuration here as it has already been sent with the app-started event
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      // no distributions or logs as they didn't fit because of the request size limit
      .assertNoMoreMessages()
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(3)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()
  }
}
