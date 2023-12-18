package datadog.telemetry

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.telemetry.dependency.DependencyService
import datadog.telemetry.dependency.LocationsCollectingTransformer
import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.Strings
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.lang.instrument.Instrumentation

class TelemetrySystemSpecification extends DDSpecification {
  Instrumentation inst = Mock()

  TelemetrySystem telemetrySystem = new TelemetrySystem()

  def cleanup() {
    telemetrySystem?.shutdown()
  }

  void 'installs dependencies transformer'() {
    when:
    def depService = telemetrySystem.createDependencyService(inst)

    then:
    1 * inst.addTransformer(_ as LocationsCollectingTransformer)
  }

  void 'create telemetry thread'() {
    setup:
    def telemetryService = Mock(TelemetryService)
    def depService = Mock(DependencyService)

    when:
    def thread = telemetrySystem.createTelemetryRunnable(telemetryService, depService, true)

    then:
    thread != null
  }

  void 'start-stop telemetry system'() {
    setup:
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.SITE), "datad0g.com")
    injectEnvConfig(Strings.toEnvVar(GeneralConfig.API_KEY), "api-key")
    def instrumentation = Mock(Instrumentation)

    when:
    telemetrySystem.start(instrumentation, sharedCommunicationObjects())

    then:
    telemetrySystem.telemetryThread != null

    when:
    telemetrySystem.shutdown()

    then:
    telemetrySystem.telemetryThread == null ||
      telemetrySystem.telemetryThread.isInterrupted() ||
      !telemetrySystem.telemetryThread.isAlive()
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
