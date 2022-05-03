package datadog.telemetry

import datadog.telemetry.api.AppDependenciesLoaded
import datadog.telemetry.api.AppIntegrationsChange
import datadog.telemetry.api.AppStarted
import datadog.telemetry.api.Dependency
import datadog.telemetry.api.DependencyType
import datadog.telemetry.api.GenerateMetrics
import datadog.telemetry.api.Integration
import datadog.telemetry.api.KeyValue
import datadog.telemetry.api.Metric
import datadog.telemetry.api.RequestType
import datadog.trace.api.time.TimeSource
import datadog.trace.test.util.DDSpecification
import okhttp3.Request

class TelemetryServiceSpecification extends DDSpecification {
  private static final Request REQUEST = new Request.Builder()
  .url('https://example.com').build()

  TimeSource timeSource = Mock()
  RequestBuilder requestBuilder = Mock()
  TelemetryServiceImpl telemetryService =
  new TelemetryServiceImpl(requestBuilder, timeSource)

  void 'addStartedRequest adds app_started event'() {
    when:
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { it.requestType.is(RequestType.APP_STARTED)}) >> REQUEST
    telemetryService.queue.peek().is(REQUEST)
  }

  void 'appClosingRequests returns an app_closing event'() {
    when:
    Request req = telemetryService.appClosingRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_CLOSING) >> REQUEST
    req.is(REQUEST)
  }

  void 'added configuration pairs are reported in app_start'() {
    when:
    def value = new KeyValue(name: 'my name', value: 'my value')
    telemetryService.addConfiguration(value)
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED &&
        p.configuration.first().with {
          return it.name == 'my name' && it.value == 'my value'
        }
    }) >> REQUEST
    0 * requestBuilder._
  }

  void 'added dependencies are report in app_start'() {
    when:
    def dep = new Dependency(
      hash: 'deadbeef', name: 'dep name', version: '1.2.3', type: DependencyType.SHAREDSYSTEMLIBRARY)
    telemetryService.addDependency(dep)
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED &&
        p.dependencies.first().with {
          return it.name == 'dep name' && it.hash == 'deadbeef' &&
            version == '1.2.3' && it.type.is(DependencyType.SHAREDSYSTEMLIBRARY)
        }
    }) >> REQUEST
    0 * requestBuilder._
  }

  void 'added dependencies are reported in app_dependencies_loaded'() {
    when:
    def dep = new Dependency(
      hash: 'deadbeef', name: 'dep name', version: '1.2.3', type: DependencyType.SHAREDSYSTEMLIBRARY)
    telemetryService.addDependency(dep)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, { AppDependenciesLoaded p ->
      p.requestType == RequestType.APP_DEPENDENCIES_LOADED &&
        p.dependencies.first().with {
          return it.name == 'dep name' && it.hash == 'deadbeef' &&
            version == '1.2.3' && it.type.is(DependencyType.SHAREDSYSTEMLIBRARY)
        }
    }) >> REQUEST
    queue.first().is(REQUEST)
    0 * requestBuilder._
  }

  void 'added integration is reported in app_start'() {
    def integration

    when:
    integration = new Integration(
      autoEnabled: true, compatible: true, enabled: true, name: 'my integration', version: '1.2.3')
    telemetryService.addIntegration(integration)
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED &&
        p.integrations.first().is(integration)
    }) >> REQUEST
    0 * requestBuilder._
  }

  void 'added integration is reported in app_integrations_change'() {
    def integration

    when:
    integration = new Integration(
      autoEnabled: true, compatible: true, enabled: true, name: 'my integration', version: '1.2.3')
    telemetryService.addIntegration(integration)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_INTEGRATIONS_CHANGE, { AppIntegrationsChange p ->
      p.requestType == RequestType.APP_INTEGRATIONS_CHANGE &&
        p.integrations.first().is(integration)
    }) >> REQUEST
    queue.first().is(REQUEST)
    0 * requestBuilder._
  }

  void 'added metrics are reported in generate_metrics'() {
    def metric

    when:
    metric = new Metric(metric: 'my metric', tags: ['my tag'],
    type: Metric.TypeEnum.GAUGE, points: [[0.1, 0.2], [0.2, 0.1]])
    telemetryService.addMetric(metric)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.GENERATE_METRICS, { GenerateMetrics p ->
      p.requestType == RequestType.GENERATE_METRICS &&
        p.libLanguage == 'java' &&
        p.namespace == 'appsec' &&
        p.libVersion == '0.0.0' &&
        p.requestType &&
        p.series.first().is(metric)
    }) >> REQUEST
    queue.first().is(REQUEST)
    0 * requestBuilder._
  }
}
