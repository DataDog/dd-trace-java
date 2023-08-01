package datadog.telemetry

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.telemetry.dependency.DependencyService
import datadog.telemetry.dependency.LocationsCollectingTransformer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import spock.lang.Specification

import java.lang.instrument.Instrumentation

class TelemetrySystemSpecification extends Specification {
  Instrumentation inst = Mock()

  void 'installs dependencies transformer'() {
    when:
    def depService = TelemetrySystem.createDependencyService(inst)

    then:
    1 * inst.addTransformer(_ as LocationsCollectingTransformer)

    cleanup:
    depService.stop()
  }

  void 'create telemetry thread'() {
    setup:
    def telemetryService = Mock(TelemetryService)
    def depService = Mock(DependencyService)

    when:
    def thread = TelemetrySystem.createTelemetryRunnable(telemetryService, depService)

    then:
    thread != null

    cleanup:
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
