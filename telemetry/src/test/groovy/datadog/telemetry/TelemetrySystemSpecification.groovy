package datadog.telemetry

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.metrics.api.Monitoring
import datadog.telemetry.dependency.DependencyService
import datadog.telemetry.dependency.LocationsCollectingTransformer
import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ConfigStrings
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.lang.instrument.Instrumentation

class TelemetrySystemSpecification extends DDSpecification {
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
    def thread = TelemetrySystem.createTelemetryRunnable(telemetryService, depService, true)

    then:
    thread != null

    cleanup:
    TelemetrySystem.stop()
  }

  void 'start-stop telemetry system'() {
    setup:
    injectEnvConfig(ConfigStrings.toEnvVar(GeneralConfig.SITE), "datad0g.com")
    injectEnvConfig(ConfigStrings.toEnvVar(GeneralConfig.API_KEY), "api-key")
    def instrumentation = Mock(Instrumentation)

    when:
    TelemetrySystem.startTelemetry(instrumentation, sharedCommunicationObjects())

    then:
    TelemetrySystem.TELEMETRY_THREAD != null

    when:
    TelemetrySystem.stop()

    then:
    TelemetrySystem.TELEMETRY_THREAD == null ||
      TelemetrySystem.TELEMETRY_THREAD.isInterrupted() ||
      !TelemetrySystem.TELEMETRY_THREAD.isAlive()
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    new SharedCommunicationObjects(
      agentHttpClient: Mock(OkHttpClient),
      monitoring: Mock(Monitoring),
      agentUrl: HttpUrl.get('https://example.com'),
      featuresDiscovery: Mock(DDAgentFeaturesDiscovery)
      )
  }
}
