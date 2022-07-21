package datadog.telemetry

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.telemetry.dependency.DependencyService
import datadog.telemetry.dependency.LocationsCollectingTransformer
import datadog.trace.test.util.DDSpecification
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

import java.lang.instrument.Instrumentation

class TelemetrySystemSpecification extends DDSpecification {
  Instrumentation inst = Mock()

  void 'installs dependencies transformer'() {
    setup:
    injectSysConfig('dd.instrumentation.telemetry.enabled', 'true')

    when:
    TelemetrySystem.createDependencyService(inst)

    then:
    1 * inst.addTransformer(_ as LocationsCollectingTransformer)
  }

  void 'create telemetry thread'() {
    setup:
    def telemetryService = Mock(TelemetryService)
    def okHttpClient = Mock(OkHttpClient)
    def depService = Mock(DependencyService)

    when:
    def thread = TelemetrySystem.createTelemetryRunnable(telemetryService, okHttpClient, depService)

    then:
    thread != null
    TelemetrySystem.stop()
  }

  void 'start-stop telemetry system'() {
    setup:
    def instrumentation = Mock(Instrumentation)

    when:
    TelemetrySystem.startTelemetry(instrumentation, sharedCommunicationObjects())

    then:
    TelemetrySystem.TELEMETRY_THREAD != null

    when:
    TelemetrySystem.stop()

    then:
    TelemetrySystem.TELEMETRY_THREAD == null ||
      TelemetrySystem.TELEMETRY_THREAD.isInterrupted()
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    new SharedCommunicationObjects(
      okHttpClient: Mock(OkHttpClient),
      monitoring: Mock(Monitoring),
      agentUrl: HttpUrl.get('https://example.com'),
      featuresDiscovery: Mock(DDAgentFeaturesDiscovery)
      )
  }
}
