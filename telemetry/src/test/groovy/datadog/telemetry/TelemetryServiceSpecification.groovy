package datadog.telemetry

import datadog.telemetry.api.AppDependenciesLoaded
import datadog.telemetry.api.AppIntegrationsChange
import datadog.telemetry.api.AppStarted
import datadog.telemetry.api.Dependency
import datadog.telemetry.api.DependencyType
import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Distributions
import datadog.telemetry.api.GenerateMetrics
import datadog.telemetry.api.Integration
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.LogMessageLevel
import datadog.telemetry.api.Logs
import datadog.telemetry.api.Metric
import datadog.telemetry.api.RequestType
import datadog.trace.api.time.TimeSource
import datadog.trace.test.util.DDSpecification
import okhttp3.Request

import java.util.function.Supplier

class TelemetryServiceSpecification extends DDSpecification {
  private static final Request REQUEST = new Request.Builder()
  .url('https://example.com').build()

  TimeSource timeSource = Mock()
  RequestBuilder requestBuilder = Mock {
    build(_ as RequestType) >> REQUEST
  }
  Supplier<RequestBuilder> requestBuilderSupplier = new Supplier<RequestBuilder>() {
    @Override
    RequestBuilder get() {
      return requestBuilder
    }
  }
  TelemetryServiceImpl telemetryService =
  new TelemetryServiceImpl(requestBuilderSupplier, timeSource, 1, 1)

  void 'heartbeat interval every 1 sec'() {
    // Time: 0 seconds - no packets yet
    when:
    def queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 0
    queue.isEmpty()

    // Time +999ms : less that 1 second passed - still no packets
    when:
    queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 999
    queue.isEmpty()

    // Time +1001ms : more than 1 second passed - heart beat generated
    when:
    queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 1001
    queue.size() == 1
    queue.clear()

    // Time +1001ms : more than 2 seconds passed - another heart beat generated
    when:
    queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 2002
    queue.size() == 1

  }

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
    telemetryService.addConfiguration('my name': 'my value')
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
      hash: 'deadbeef', name: 'dep name', version: '1.2.3', type: DependencyType.SHARED_SYSTEM_LIBRARY)
    telemetryService.addDependency(dep)
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, { AppStarted p ->
      p.requestType == RequestType.APP_STARTED &&
        p.dependencies.first().with {
          return it.name == 'dep name' && it.hash == 'deadbeef' &&
            version == '1.2.3' && it.type.is(DependencyType.SHARED_SYSTEM_LIBRARY)
        }
    }) >> REQUEST
    0 * requestBuilder._
  }

  void 'added dependencies are reported in app_dependencies_loaded'() {
    when:
    def dep = new Dependency(
      hash: 'deadbeef', name: 'dep name', version: '1.2.3', type: DependencyType.SHARED_SYSTEM_LIBRARY)
    telemetryService.addDependency(dep)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, { AppDependenciesLoaded p ->
      p.requestType == RequestType.APP_DEPENDENCIES_LOADED &&
        p.dependencies.first().with {
          return it.name == 'dep name' && it.hash == 'deadbeef' &&
            version == '1.2.3' && it.type.is(DependencyType.SHARED_SYSTEM_LIBRARY)
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
    metric = new Metric(namespace: 'appsec', metric: 'my metric', tags: ['my tag'],
    type: Metric.TypeEnum.GAUGE, points: [[0.1, 0.2], [0.2, 0.1]])
    telemetryService.addMetric(metric)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.GENERATE_METRICS, { GenerateMetrics p ->
      p.requestType == RequestType.GENERATE_METRICS &&
        p.namespace == 'tracers' &&  // top level namespace is "tracers" by default
        p.requestType &&
        p.series.first().is(metric)
    }) >> REQUEST
    queue.first().is(REQUEST)
    0 * requestBuilder._
  }

  void 'added distribution series are reported in distributions'() {
    def series

    when:
    series = new DistributionSeries(namespace: 'appsec', metric: 'my metric', tags: ['my tag'], points: [[0.1, 0.2], [0.2, 0.1]])
    telemetryService.addDistributionSeries(series)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.DISTRIBUTIONS, { Distributions p ->
      p.requestType == RequestType.DISTRIBUTIONS &&
        p.namespace == 'tracers' &&  // top level namespace is "tracers" by default
        p.requestType &&
        p.series.first().is(series)
    }) >> REQUEST
    queue.first().is(REQUEST)
    0 * requestBuilder._
  }

  void 'send #messages log messages in #requests requests'() {
    def logMessage
    def telemetry

    when:
    telemetry = new TelemetryServiceImpl(requestBuilderSupplier, timeSource, 1, 1, 10, 1)
    logMessage = new LogMessage(message: 'hello world', level: LogMessageLevel.DEBUG)
    for (int i=0; i<messages; i++) {
      telemetry.addLogMessage(logMessage)
    }
    def queue = telemetry.prepareRequests()

    then:
    requests * requestBuilder.build(RequestType.LOGS, { Logs p ->
      p.requestType == RequestType.LOGS &&
        p.messages.first().is(logMessage)
    }) >> REQUEST
    queue.first().is(REQUEST)
    0 * requestBuilder._

    where:
    messages    | requests
    10        | 1
    11        | 2
    100       | 10
  }

  void 'send max 10 dependencies per request'() {
    def dep
    def telemetry

    when:
    telemetry = new TelemetryServiceImpl(requestBuilderSupplier, timeSource, 1, 1, 1, 10)
    dep = new Dependency(name: 'dep')
    for (int i=0; i<15; i++) {
      telemetry.addDependency(dep)
    }
    def queue = telemetry.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, { AppDependenciesLoaded p ->
      p.requestType == RequestType.APP_DEPENDENCIES_LOADED &&
        p.dependencies.size() == 10
    }) >> REQUEST
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, { AppDependenciesLoaded p ->
      p.requestType == RequestType.APP_DEPENDENCIES_LOADED &&
        p.dependencies.size() == 5
    }) >> REQUEST
    queue.first().is(REQUEST)
    0 * requestBuilder._
  }
}
