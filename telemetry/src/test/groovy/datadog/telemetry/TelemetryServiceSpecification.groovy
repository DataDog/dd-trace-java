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

  TestHttpClient testHttpClient = new TestHttpClient()

  def configuration = ["confkey": "confvalue"]
  def confKeyValue = new ConfigChange("confkey", "confvalue")
  def integration = new Integration("integration", true)
  def dependency = new Dependency("dependency", "1.0.0", "src", "hash")
  def metric = new Metric().namespace("tracers").metric("metric").points([[1, 2]]).tags(["tag1", "tag2"])
  def distribution = new DistributionSeries().namespace("tracers").metric("distro").points([1, 2, 3]).tags(["tag1", "tag2"]).common(false)
  def logMessage = new LogMessage().message("log-message").tags("tag1:tag2").level(LogMessageLevel.DEBUG).stackTrace("stack-trace").tracerTime(32423)
  TelemetryService telemetryService = new TelemetryService(testHttpClient, HttpUrl.get("https://example.com"))

  void 'happy path without data'() {
    when: 'first iteration'
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then: 'app-started'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
      .assertPayload()
      .configuration(null)
      .dependencies([])
      .integrations([])
    testHttpClient.assertNoMoreRequests()

    when: 'second iteration'
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then: 'app-heartbeat only'
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT)
    testHttpClient.assertNoMoreRequests()
  }

  void 'happy path with data before app-started'() {
    when: 'add data before first iteration'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and: 'send messages'
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
      .assertPayload()
      .configuration([confKeyValue])
      .dependencies([dependency])
      .integrations([integration])
    testHttpClient.assertNoMoreRequests()

    when: 'second iteration'
    testHttpClient.expectRequests(4, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT)
    testHttpClient.assertRequestBody(RequestType.GENERATE_METRICS)
      .assertPayload()
      .namespace("tracers")
      .metrics([metric])
    testHttpClient.assertRequestBody(RequestType.DISTRIBUTIONS)
      .assertPayload()
      .namespace("tracers")
      .distributionSeries([distribution])
    testHttpClient.assertRequestBody(RequestType.LOGS)
      .assertPayload()
      .logs([logMessage])
    testHttpClient.assertNoMoreRequests()
  }

  void 'happy path with data after app-started'() {
    when: 'send messages'
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
      .assertPayload()
      .configuration(null)
      .dependencies([])
      .integrations([])
    testHttpClient.assertNoMoreRequests()

    when: 'add data after first iteration'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and: 'send messages'
    testHttpClient.expectRequests(7, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT)
    testHttpClient.assertRequestBody(RequestType.APP_CLIENT_CONFIGURATION_CHANGE)
      .assertPayload()
      .configuration([confKeyValue])
    testHttpClient.assertRequestBody(RequestType.APP_INTEGRATIONS_CHANGE)
      .assertPayload()
      .integrations([integration])
    testHttpClient.assertRequestBody(RequestType.APP_DEPENDENCIES_LOADED)
      .assertPayload()
      .dependencies([dependency])
    testHttpClient.assertRequestBody(RequestType.GENERATE_METRICS)
      .assertPayload()
      .metrics([metric])
    testHttpClient.assertRequestBody(RequestType.DISTRIBUTIONS)
      .assertPayload()
      .distributionSeries([distribution])
    testHttpClient.assertRequestBody(RequestType.LOGS)
      .assertPayload()
      .logs([logMessage])
    testHttpClient.assertNoMoreRequests()
  }

  void 'no message before app-started'() {
    when: 'attempt with 404 error'
    testHttpClient.expectRequests(1, HttpClient.Result.NOT_FOUND)
    telemetryService.sendIntervalRequests()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
      .assertPayload()
      .configuration(null)
      .dependencies([])
      .integrations([])
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with 500 error'
    testHttpClient.expectRequests(1, HttpClient.Result.FAILURE)
    telemetryService.sendIntervalRequests()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
      .assertPayload()
      .configuration(null)
      .dependencies([])
      .integrations([])
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with unexpected FAILURE (e.g. 100 http status code) (not valid)'
    testHttpClient.expectRequests(1, HttpClient.Result.FAILURE)
    telemetryService.sendIntervalRequests()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
      .assertPayload()
      .configuration(null)
      .dependencies([])
      .integrations([])
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with success'
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
      .assertPayload()
      .configuration(null)
      .dependencies([])
      .integrations([])
    testHttpClient.assertNoMoreRequests()
  }

  void 'NOT_FOUND (e.g. 404 http status code) at #requestType prevents further messages'() {
    when: 'initial iteration'
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
    testHttpClient.assertNoMoreRequests()

    when: 'add data'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and: 'send messages'
    testHttpClient.expectRequests(prevCalls, HttpClient.Result.SUCCESS)
    testHttpClient.expectRequests(1, HttpClient.Result.NOT_FOUND)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequests(prevCalls)
    testHttpClient.assertRequestBody(requestType)

    and: 'no further requests after the first 404'
    testHttpClient.assertNoMoreRequests()

    where:
    requestType                                 | prevCalls
    RequestType.APP_HEARTBEAT                   | 0
    RequestType.APP_CLIENT_CONFIGURATION_CHANGE | 1
    RequestType.APP_INTEGRATIONS_CHANGE         | 2
    RequestType.APP_DEPENDENCIES_LOADED         | 3
    RequestType.GENERATE_METRICS                | 4
    RequestType.DISTRIBUTIONS                   | 5
    RequestType.LOGS                            | 6
  }

  void 'FAILURE (e.g. 500 http status code) at #requestType does not prevents further messages'() {
    when: 'initial iteration'
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED)
    testHttpClient.assertNoMoreRequests()

    when: 'add data'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and: 'send messages'
    testHttpClient.expectRequests(prevCalls, HttpClient.Result.SUCCESS)
    testHttpClient.expectRequests(1, HttpClient.Result.FAILURE)
    testHttpClient.expectRequests(afterCalls, HttpClient.Result.SUCCESS)
    telemetryService.sendIntervalRequests()

    then:
    testHttpClient.assertRequests(prevCalls)
    testHttpClient.assertRequestBody(requestType)

    then: 'requests continue after first 500'
    testHttpClient.assertRequests(afterCalls)
    testHttpClient.assertNoMoreRequests()

    where:
    requestType                                 | prevCalls | afterCalls
    RequestType.APP_HEARTBEAT                   | 0         | 6
    RequestType.APP_CLIENT_CONFIGURATION_CHANGE | 1         | 5
    RequestType.APP_INTEGRATIONS_CHANGE         | 2         | 4
    RequestType.APP_DEPENDENCIES_LOADED         | 3         | 3
    RequestType.GENERATE_METRICS                | 4         | 2
    RequestType.DISTRIBUTIONS                   | 5         | 1
    RequestType.LOGS                            | 6         | 0
  }

  void 'Send closing event request'() {
    when:
    testHttpClient.expectRequests(1, HttpClient.Result.SUCCESS)
    telemetryService.sendAppClosingRequest()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_CLOSING)
  }
}
