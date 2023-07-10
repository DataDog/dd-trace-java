package datadog.telemetry

import datadog.telemetry.api.AppClientConfigurationChange
import datadog.telemetry.api.AppDependenciesLoaded
import datadog.telemetry.api.AppIntegrationsChange
import datadog.telemetry.api.AppStarted
import datadog.telemetry.api.Dependency
import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Distributions
import datadog.telemetry.api.GenerateMetrics
import datadog.telemetry.api.Integration
import datadog.telemetry.api.KeyValue
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.Logs
import datadog.telemetry.api.Metric
import datadog.telemetry.api.RequestType
import datadog.trace.test.util.DDSpecification
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

import java.util.function.Supplier

class TelemetryServiceSpecification extends DDSpecification {

  OkHttpClient httpClient = Mock()
  RequestBuilder requestBuilder = Spy(new RequestBuilder(HttpUrl.get("https://example.com")))
  Supplier<RequestBuilder> requestBuilderSupplier = { requestBuilder }
  TelemetryService telemetryService = new TelemetryService(httpClient, requestBuilderSupplier)

  def dummyRequest = new Request.Builder().url(HttpUrl.get("https://example.com")).build()

  Call mockResponse(int code) {
    Stub(Call) {
      execute() >> {
        new Response.Builder()
          .request(dummyRequest)
          .protocol(Protocol.HTTP_1_1)
          .message("OK")
          .body(ResponseBody.create(MediaType.get("text/plain"), "OK"))
          .code(code)
          .build()
      }
    }
  }

  def okResponse = mockResponse(202)
  def continueResponse = mockResponse(100)
  def notFoundResponse = mockResponse(404)
  def serverErrorResponse = mockResponse(500)

  def configuration = ["confkey": "confvalue"]
  def confKeyValue = new KeyValue().name("confkey").value("confvalue")
  def integration = new Integration().name("integration").enabled(true)
  def dependency = new Dependency().name("dependency").version("1.0.0")
  def metric = new Metric().namespace("tracers").metric("metric").points([[1, 2]])
  def distribution = new DistributionSeries().namespace("tracers").metric("distro").points([1, 2, 3])
  def logMessage = new LogMessage().message("log-message")

  void 'happy path without data'() {
    when: 'first iteration'
    telemetryService.sendIntervalRequests()

    then: 'app-started'
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED
      p.configuration == null
      p.dependencies.isEmpty()
      p.integrations.isEmpty()
    })
    1 * httpClient.newCall(_) >> okResponse
    0 * _

    when: 'second iteration'
    telemetryService.sendIntervalRequests()

    then: 'only heartbeat'
    1 * requestBuilder.build(RequestType.APP_HEARTBEAT, null)
    1 * httpClient.newCall(_) >> okResponse
    0 * _
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
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED
      p.configuration == [confKeyValue]
      p.dependencies == [dependency]
      p.integrations == [integration]
    })
    1 * httpClient.newCall(_) >> okResponse
    0 * _

    when:
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_HEARTBEAT, null)
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.GENERATE_METRICS, { GenerateMetrics p ->
      p.series == [metric]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.DISTRIBUTIONS, { Distributions p ->
      p.series == [distribution]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.LOGS, { Logs p ->
      p.messages == [logMessage]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    0 * _
  }

  void 'happy path with data after app-started'() {
    when: 'send messages'
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED
      p.configuration == null
      p.dependencies == []
      p.integrations == []
    })
    1 * httpClient.newCall(_) >> okResponse
    0 * _

    when: 'add data after first iteration'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and: 'send messages'
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_HEARTBEAT, null)
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.APP_CLIENT_CONFIGURATION_CHANGE, { AppClientConfigurationChange p ->
      p.configuration == [confKeyValue]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.APP_INTEGRATIONS_CHANGE, { AppIntegrationsChange p ->
      p.integrations == [integration]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, { AppDependenciesLoaded p ->
      p.dependencies == [dependency]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.GENERATE_METRICS, { GenerateMetrics p ->
      p.series == [metric]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.DISTRIBUTIONS, { Distributions p ->
      p.series == [distribution]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(RequestType.LOGS, { Logs p ->
      p.messages == [logMessage]
    })
    1 * httpClient.newCall(_) >> okResponse

    then:
    0 * _
  }

  void 'no message before app-started'() {
    when: 'attempt with 404 error'
    telemetryService.sendIntervalRequests()

    then: 'app-started is attempted'
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED
      p.configuration == null
      p.dependencies.isEmpty()
      p.integrations.isEmpty()
    })
    1 * httpClient.newCall(_) >> notFoundResponse
    0 * _

    when: 'attempt with 500 error'
    telemetryService.sendIntervalRequests()

    then: 'app-started is attempted'
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED
      p.configuration == null
      p.dependencies.isEmpty()
      p.integrations.isEmpty()
    })
    1 * httpClient.newCall(_) >> serverErrorResponse
    0 * _

    when: 'attempt with unexpected 200 code (not valid)'
    telemetryService.sendIntervalRequests()

    then: 'app-started is attempted'
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED
      p.configuration == null
      p.dependencies.isEmpty()
      p.integrations.isEmpty()
    })
    1 * httpClient.newCall(_) >> continueResponse
    0 * _

    when: 'attempt with success'
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED
      p.configuration == null
      p.dependencies.isEmpty()
      p.integrations.isEmpty()
    })
    1 * httpClient.newCall(_) >> okResponse
    0 * _
  }

  void '404 at #requestType prevents further messages'() {
    when: 'initial iteration'
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(_, _)
    1 * httpClient.newCall(_) >> okResponse
    0 * _

    when: 'add data'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and:
    telemetryService.sendIntervalRequests()

    then:
    prevCalls * requestBuilder.build(_, _)
    prevCalls * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(requestType, _)
    1 * httpClient.newCall(_) >> notFoundResponse

    then: 'no further requests after the first 404'
    0 * _

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

  void '500 at #requestType does not prevents further messages'() {
    when: 'initial iteration'
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(_, _)
    1 * httpClient.newCall(_) >> okResponse
    0 * _

    when: 'add data'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and:
    telemetryService.sendIntervalRequests()

    then:
    prevCalls * requestBuilder.build(_, _)
    prevCalls * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(requestType, _)
    1 * httpClient.newCall(_) >> serverErrorResponse

    then: 'requests continue after first 500'
    afterCalls * requestBuilder.build(_, _)
    afterCalls * httpClient.newCall(_) >> okResponse
    0 * _

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

  void 'Exception at #requestType does not prevents further messages'() {
    when: 'initial iteration'
    telemetryService.sendIntervalRequests()

    then:
    1 * requestBuilder.build(_, _)
    1 * httpClient.newCall(_) >> okResponse
    0 * _

    when: 'add data'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)

    and:
    telemetryService.sendIntervalRequests()

    then:
    prevCalls * requestBuilder.build(_, _)
    prevCalls * httpClient.newCall(_) >> okResponse

    then:
    1 * requestBuilder.build(requestType, _)
    1 * httpClient.newCall(_) >> { throw new IOException("exception") }

    then: 'requests continue after first exception'
    afterCalls * requestBuilder.build(_, _)
    afterCalls * httpClient.newCall(_) >> okResponse
    0 * _

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
}
