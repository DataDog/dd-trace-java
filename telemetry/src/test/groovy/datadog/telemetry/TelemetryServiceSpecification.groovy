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
import okhttp3.HttpUrl
import okhttp3.Request

import java.util.function.Supplier

class TelemetryServiceSpecification extends DDSpecification {
  private static final Request REQUEST = new Request.Builder()
  .url('https://example.com')
  .build()

  TimeSource timeSource = Mock()
  RequestBuilder requestBuilder = Spy(new RequestBuilder(HttpUrl.get("https://example.com")))
  Supplier<RequestBuilder> requestBuilderSupplier = () -> requestBuilder
  TelemetryService telemetryService =
  new TelemetryService(requestBuilderSupplier, timeSource, 60, 10)

  String getRequestType(Request request) {
    request.header("DD-Telemetry-Request-Type")
  }

  void 'heartbeat message on every heartbeat interval'() {
    // Time: 0 seconds - no packets yet
    when:
    def queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 0
    queue.isEmpty()

    // Time +9999ms : less that 10 seconds passed (below metrics interval) - still no packets
    when:
    queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 9999
    queue.isEmpty()

    // Time +59999ms : less that 60 seconds passed (below heartbeat interval) - still no packets
    when:
    queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 59999
    queue.isEmpty()

    // Time +1001ms : more than 60 seconds passed (above heartbeat interval) - heart beat generated
    when:
    queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 60001
    queue.size() == 1
    getRequestType(queue.poll()) == "app-heartbeat"
    queue.clear()

    // Time +120002ms : more than 120 seconds passed - another heart beat generated
    when:
    queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 120002
    queue.size() == 1
    getRequestType(queue.poll()) == "app-heartbeat"
  }

  void 'heartbeat is sent even if there are other messages'() {
    setup:
    final logMessage = new LogMessage(message: 'hello world', level: LogMessageLevel.DEBUG)

    when:
    telemetryService.addLogMessage(logMessage)
    def queue = telemetryService.prepareRequests()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 60001
    queue.size() == 2
    queue.collect {
      getRequestType(it)
    }.toSet() == ["logs", "app-heartbeat"].toSet()
  }

  void 'addStartedRequest adds app-started event'() {
    when:
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, {
      it.requestType.is(RequestType.APP_STARTED)
    })
    0 * _
    getRequestType(telemetryService.queue.poll()) == "app-started"
  }

  void 'appClosingRequests returns an app-closing event'() {
    when:
    Request req = telemetryService.appClosingRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_CLOSING)
    getRequestType(req) == "app-closing"
  }

  void 'added configuration pairs are reported in app-started'() {
    when:
    telemetryService.addConfiguration('my name': 'my value')
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, {
      AppStarted p ->
      p.requestType == RequestType.APP_STARTED &&
      p.configuration.first().with {
        return it.name == 'my name' && it.value == 'my value'
      }
    })
    0 * requestBuilder._
    getRequestType(telemetryService.queue.poll()) == "app-started"
  }

  void 'added dependencies are report in app-started'() {
    when:
    def dep = new Dependency(
    hash: 'deadbeef', name: 'dep name', version: '1.2.3', type: DependencyType.SHARED_SYSTEM_LIBRARY)
    telemetryService.addDependency(dep)
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, {
      AppStarted p ->
      p.requestType == RequestType.APP_STARTED &&
      p.dependencies.first().with {
        return it.name == 'dep name' && it.hash == 'deadbeef' &&
        version == '1.2.3' && it.type.is(DependencyType.SHARED_SYSTEM_LIBRARY)
      }
    })
    0 * requestBuilder._
    getRequestType(telemetryService.queue.poll()) == "app-started"
  }

  void 'added dependencies are reported in app-dependencies-loaded'() {
    when:
    def dep = new Dependency(
    hash: 'deadbeef', name: 'dep name', version: '1.2.3', type: DependencyType.SHARED_SYSTEM_LIBRARY)
    telemetryService.addDependency(dep)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, {
      AppDependenciesLoaded p ->
      p.requestType == RequestType.APP_DEPENDENCIES_LOADED &&
      p.dependencies.first().with {
        return it.name == 'dep name' && it.hash == 'deadbeef' &&
        version == '1.2.3' && it.type.is(DependencyType.SHARED_SYSTEM_LIBRARY)
      }
    })
    0 * requestBuilder._
    getRequestType(queue.poll()) == "app-dependencies-loaded"
  }

  void 'added integration is reported in app-started'() {
    def integration

    when:
    integration = new Integration(
    autoEnabled: true, compatible: true, enabled: true, name: 'my integration', version: '1.2.3')
    telemetryService.addIntegration(integration)
    telemetryService.addStartedRequest()

    then:
    1 * requestBuilder.build(RequestType.APP_STARTED, {
      AppStarted p ->
      p.requestType == RequestType.APP_STARTED &&
      p.integrations.first().is(integration)
    })
    0 * requestBuilder._
    getRequestType(telemetryService.queue.poll()) == "app-started"
  }

  void 'added integration is reported in app-integrations-change'() {
    def integration

    when:
    integration = new Integration(
    autoEnabled: true, compatible: true, enabled: true, name: 'my integration', version: '1.2.3')
    telemetryService.addIntegration(integration)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_INTEGRATIONS_CHANGE, {
      AppIntegrationsChange p ->
      p.requestType == RequestType.APP_INTEGRATIONS_CHANGE &&
      p.integrations.first().is(integration)
    })
    0 * requestBuilder._
    queue.size() == 1
    getRequestType(queue.poll()) == "app-integrations-change"
  }

  void 'added metrics are reported in generate-metrics'() {
    def metric

    when:
    metric = new Metric(namespace: 'appsec', metric: 'my metric', tags: ['my tag'],
    type: Metric.TypeEnum.GAUGE, points: [[0.1, 0.2], [0.2, 0.1]])
    telemetryService.addMetric(metric)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.GENERATE_METRICS, {
      GenerateMetrics p ->
      p.requestType == RequestType.GENERATE_METRICS &&
      p.namespace == 'tracers' &&  // top level namespace is "tracers" by default
      p.requestType &&
      p.series.first().is(metric)
    })
    0 * requestBuilder._
    getRequestType(queue.poll()) == "generate-metrics"
  }

  void 'added distribution series are reported in distributions'() {
    def series

    when:
    series = new DistributionSeries(namespace: 'appsec', metric: 'my metric', tags: ['my tag'], points: [1, 2, 2, 3])
    telemetryService.addDistributionSeries(series)
    def queue = telemetryService.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.DISTRIBUTIONS, {
      Distributions p ->
      p.requestType == RequestType.DISTRIBUTIONS &&
      p.namespace == 'tracers' &&  // top level namespace is "tracers" by default
      p.requestType &&
      p.series.first().is(series)
    })
    0 * requestBuilder._
    getRequestType(queue.poll()) == "distributions"
  }

  void 'send #messages log messages in #requests requests'() {
    def logMessage

    when:
    def telemetry = new TelemetryService(requestBuilderSupplier, timeSource, 1, 1, 10, 1)
    logMessage = new LogMessage(message: 'hello world', level: LogMessageLevel.DEBUG)
    for (int i=0; i<messages; i++) {
      telemetry.addLogMessage(logMessage)
    }
    def queue = telemetry.prepareRequests()

    then:
    requests * requestBuilder.build(RequestType.LOGS, {
      Logs p ->
      p.requestType == RequestType.LOGS &&
      p.messages.first().is(logMessage)
    })
    0 * requestBuilder._
    getRequestType(queue.poll()) == "logs"

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
    telemetry = new TelemetryService(requestBuilderSupplier, timeSource, 1, 1, 1, 10)
    dep = new Dependency(name: 'dep')
    for (int i=0; i<15; i++) {
      telemetry.addDependency(dep)
    }
    def queue = telemetry.prepareRequests()

    then:
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, {
      AppDependenciesLoaded p ->
      p.requestType == RequestType.APP_DEPENDENCIES_LOADED &&
      p.dependencies.size() == 10
    })
    1 * requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, {
      AppDependenciesLoaded p ->
      p.requestType == RequestType.APP_DEPENDENCIES_LOADED &&
      p.dependencies.size() == 5
    })
    0 * requestBuilder._
    getRequestType(queue.poll()) == "app-dependencies-loaded"
  }
}
