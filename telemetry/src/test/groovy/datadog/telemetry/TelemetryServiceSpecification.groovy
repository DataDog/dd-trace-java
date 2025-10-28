package datadog.telemetry

import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Integration
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.LogMessageLevel
import datadog.telemetry.api.Metric
import datadog.telemetry.api.RequestType
import datadog.telemetry.dependency.Dependency
import datadog.trace.api.ConfigOrigin
import datadog.trace.api.ConfigSetting
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.DebuggerConfig
import datadog.trace.api.config.ProfilingConfig
import datadog.trace.api.telemetry.Endpoint
import datadog.trace.api.telemetry.ProductChange
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ConfigStrings

class TelemetryServiceSpecification extends DDSpecification {
  def confKeyOrigin = ConfigOrigin.DEFAULT
  def confKeyValue = ConfigSetting.of("confkey", "confvalue", confKeyOrigin)
  def configuration = [confKeyOrigin: [confkey: confKeyValue]]
  def integration = new Integration("integration", true)
  def dependency = new Dependency("dependency", "1.0.0", "src", "hash")
  def metric = new Metric().namespace("tracers").metric("metric").points([[1, 2]]).tags(["tag1", "tag2"])
  def distribution = new DistributionSeries().namespace("tracers").metric("distro").points([1, 2, 3]).tags(["tag1", "tag2"]).common(false)
  def logMessage = new LogMessage().message("log-message").tags("tag1:tag2").level(LogMessageLevel.DEBUG).stackTrace("stack-trace").tracerTime(32423).count(1)
  def productChange = new ProductChange().productType(ProductChange.ProductType.APPSEC).enabled(true)
  def endpoint = new Endpoint().first(true).type('REST').method("GET").operation('http.request').resource("GET /test").path("/test").requestBodyType(['application/json']).responseBodyType(['application/json']).responseCode([200]).authentication(['JWT'])

  def 'happy path without data'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    when: 'first iteration'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

    then: 'app-started'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products()
    testHttpClient.assertNoMoreRequests()

    when: 'second iteration'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'app-heartbeat only'
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT)
    testHttpClient.assertNoMoreRequests()

    when: 'third iteration'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'app-heartbeat only'
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT)
    testHttpClient.assertNoMoreRequests()
  }

  def 'happy path with data'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    when: 'add data before first iteration'
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)
    telemetryService.addProductChange(productChange)
    telemetryService.addEndpoint(endpoint)

    and: 'send messages'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload()
      .products()
      .configuration([confKeyValue])

    when:
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(8)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      // no configuration here as it has already been sent with the app-started event
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNextMessage(RequestType.APP_PRODUCT_CHANGE).hasPayload().productChange(productChange)
      .assertNextMessage(RequestType.APP_ENDPOINTS).hasPayload().endpoint(endpoint)
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()

    when: 'second iteration heartbeat only'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).assertNoPayload()
    testHttpClient.assertNoMoreRequests()

    when: 'third iteration metrics data'
    telemetryService.addMetric(metric)
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(2)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()
  }

  def 'happy path with data after app-started'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    when: 'send messages'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

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
    telemetryService.addProductChange(productChange)
    telemetryService.addEndpoint(endpoint)

    and: 'send messages'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(9)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      .assertNextMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE).hasPayload().configuration([confKeyValue])
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNextMessage(RequestType.APP_PRODUCT_CHANGE).hasPayload().productChange(productChange)
      .assertNextMessage(RequestType.APP_ENDPOINTS).hasPayload().endpoint(endpoint)
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()
  }

  def 'do not discard data for app-started event until it has been successfully sent'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)
    telemetryService.addConfiguration(configuration)

    when: 'attempt with 404 error'
    testHttpClient.expectRequest(TelemetryClient.Result.NOT_FOUND)
    !telemetryService.sendAppStartedEvent()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products().configuration([confKeyValue])
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with 500 error'
    testHttpClient.expectRequest(TelemetryClient.Result.FAILURE)
    !telemetryService.sendAppStartedEvent()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products().configuration([confKeyValue])
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with unexpected FAILURE (not valid)'
    testHttpClient.expectRequest(TelemetryClient.Result.FAILURE)
    !telemetryService.sendAppStartedEvent()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products().configuration([confKeyValue])
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with success'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

    then: 'app-started is attempted'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products().configuration([confKeyValue])
    testHttpClient.assertNoMoreRequests()
  }

  def 'resend data on successful attempt after a failure'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)
    telemetryService.addProductChange(productChange)
    telemetryService.addEndpoint(endpoint)

    when: 'attempt with NOT_FOUND error'
    testHttpClient.expectRequest(TelemetryClient.Result.NOT_FOUND)
    !telemetryService.sendAppStartedEvent()

    then: 'app-started attempted with config'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products().configuration([confKeyValue])
    testHttpClient.assertNoMoreRequests()

    when: 'successful app-started attempt'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

    then: 'attempt app-started  with SUCCESS'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products().configuration([confKeyValue])

    when: 'successful batch attempt'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'attempt batch with SUCCESS'
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(8)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      // no configuration here as it has already been sent with the app-started event
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNextMessage(RequestType.APP_PRODUCT_CHANGE).hasPayload().productChange(productChange)
      .assertNextMessage(RequestType.APP_ENDPOINTS).hasPayload().endpoint(endpoint)
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()

    when: 'attempt with NOT_FOUND error'
    testHttpClient.expectRequest(TelemetryClient.Result.NOT_FOUND)
    telemetryService.sendTelemetryEvents()

    then: 'message-batch attempted with heartbeat'
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).assertNoPayload()
    testHttpClient.assertNoMoreRequests()
  }

  def 'send closing event request'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    when:
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppClosingEvent()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_CLOSING)
    testHttpClient.assertNoMoreRequests()
  }

  def 'report when both OTel and OT are enabled'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = Spy(new TelemetryService(testHttpClient, 1000, false))
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

  def 'split telemetry requests if the size above the limit'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 5000, false)

    when: 'send a heartbeat request without telemetry data to measure body size to set stable request size limit'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'get body size'
    def bodySize = testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).bodySize()
    bodySize > 0

    when: 'sending first part of data'
    telemetryService = new TelemetryService(testHttpClient, bodySize + 510, false)

    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)
    telemetryService.addMetric(metric)
    telemetryService.addDistributionSeries(distribution)
    telemetryService.addLogMessage(logMessage)
    telemetryService.addProductChange(productChange)
    telemetryService.addEndpoint(endpoint)

    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendTelemetryEvents()

    then: 'attempt with SUCCESS'
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(5)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      .assertNextMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE).hasPayload().configuration([confKeyValue])
      .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE).hasPayload().integrations([integration])
      .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED).hasPayload().dependencies([dependency])
      .assertNextMessage(RequestType.GENERATE_METRICS).hasPayload().namespace("tracers").metrics([metric])
      // no more data fit this message is sent in the next message
      .assertNoMoreMessages()

    when: 'sending second part of data'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    !telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)
      .assertBatch(5)
      .assertFirstMessage(RequestType.APP_HEARTBEAT).hasNoPayload()
      .assertNextMessage(RequestType.DISTRIBUTIONS).hasPayload().namespace("tracers").distributionSeries([distribution])
      .assertNextMessage(RequestType.LOGS).hasPayload().logs([logMessage])
      .assertNextMessage(RequestType.APP_PRODUCT_CHANGE).hasPayload().productChange(productChange)
      .assertNextMessage(RequestType.APP_ENDPOINTS).hasPayload().endpoint(endpoint)
      .assertNoMoreMessages()
    testHttpClient.assertNoMoreRequests()
  }

  def 'send all collected data with extended-heartbeat request every time'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)

    when:
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendExtendedHeartbeat()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_EXTENDED_HEARTBEAT)
      .assertPayload()
      .configuration([confKeyValue])
      .integrations([integration])
      .dependencies([dependency])
    testHttpClient.assertNoMoreRequests()

    when:
    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)

    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendExtendedHeartbeat()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_EXTENDED_HEARTBEAT)
      .assertPayload()
      .configuration([confKeyValue, confKeyValue])
      .integrations([integration, integration])
      .dependencies([dependency, dependency])
    testHttpClient.assertNoMoreRequests()
  }

  def 'send extended-heartbeat request, even if data already has been sent or attempted as part of another telemetry events'() {
    setup:
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    telemetryService.addConfiguration(configuration)
    telemetryService.addIntegration(integration)
    telemetryService.addDependency(dependency)

    when:
    testHttpClient.expectRequest(resultCode)
    telemetryService.sendTelemetryEvents()

    then:
    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH)

    when:
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendExtendedHeartbeat()

    then:
    testHttpClient.assertRequestBody(RequestType.APP_EXTENDED_HEARTBEAT)
      .assertPayload()
      .configuration([confKeyValue])
      .integrations([integration])
      .dependencies([dependency])
    testHttpClient.assertNoMoreRequests()

    where:
    resultCode << [
      TelemetryClient.Result.SUCCESS,
      TelemetryClient.Result.FAILURE,
      TelemetryClient.Result.NOT_FOUND
    ]
  }

  def 'app can propagate configuration id'() {
    setup:
    String instrKey = 'instrumentation_config_id'
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)
    def configMap = [(instrKey): ConfigSetting.of(instrKey, id, ConfigOrigin.ENV)]
    telemetryService.addConfiguration([(ConfigOrigin.ENV): configMap])

    when: 'first iteration'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

    then: 'app-started'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().instrumentationConfigId(id)
    testHttpClient.assertNoMoreRequests()

    where:
    id << ["foo", null, ""]
  }

  def 'app started must have install signature'() {
    setup:
    injectEnvConfig("INSTRUMENTATION_INSTALL_ID", installId)
    injectEnvConfig("INSTRUMENTATION_INSTALL_TYPE", installType)
    injectEnvConfig("INSTRUMENTATION_INSTALL_TIME", installTime)

    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    when: 'first iteration'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

    then: 'app-started'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().installSignature(installId, installType, installTime)
    testHttpClient.assertNoMoreRequests()

    where:
    installId                              | installType       | installTime
    null                                   | null              | null
    null                                   | null              | "1703188334"
    null                                   | "k8s_single_step" | null
    null                                   | "k8s_single_step" | "1703188212"
    "68e75c99-57ca-4a12-adfc-575c4b05fcbe" | null              | null
    "68e75c48-57ca-4a12-adfc-575c4b05bfff" | null              | "1704183412"
    "68e75c55-57ca-4a12-adfc-575c4b05aaaa" | "k8s_single_step" | null
    "68e75c77-57ca-4a12-adfc-575c4b05fc44" | "k8s_single_step" | "1993188215"
  }

  def 'app-started must include activated products info'() {
    setup:
    injectEnvConfig(ConfigStrings.toEnvVar(AppSecConfig.APPSEC_ENABLED), appsecConfig)
    injectEnvConfig(ConfigStrings.toEnvVar(ProfilingConfig.PROFILING_ENABLED), profilingConfig)
    injectEnvConfig(ConfigStrings.toEnvVar(DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED), dynInstrConfig)

    TestTelemetryRouter testHttpClient = new TestTelemetryRouter()
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false)

    when: 'first iteration'
    testHttpClient.expectRequest(TelemetryClient.Result.SUCCESS)
    telemetryService.sendAppStartedEvent()

    then: 'app-started'
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products(appsecEnabled, profilingEnabled, dynInstrEnabled)
    testHttpClient.assertNoMoreRequests()

    where:
    appsecConfig | appsecEnabled | profilingConfig | profilingEnabled | dynInstrConfig | dynInstrEnabled
    "1"          | true          | "1"             | true             | "1"            | true
    "1"          | true          | "1"             | true             | "0"            | false
    "1"          | true          | "0"             | false            | "1"            | true
    "1"          | true          | "0"             | false            | "0"            | false
    "0"          | false         | "0"             | false            | "0"            | false
    "inactive"   | true          | "0"             | false            | "0"            | false
  }
}
