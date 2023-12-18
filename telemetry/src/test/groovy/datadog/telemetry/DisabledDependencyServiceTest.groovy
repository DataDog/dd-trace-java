package datadog.telemetry

import datadog.telemetry.dependency.LocationsCollectingTransformer
import datadog.trace.test.util.DDSpecification

import java.lang.instrument.Instrumentation

class DisabledDependencyServiceTest extends DDSpecification {

  Instrumentation inst = Mock()

  TelemetrySystem telemetrySystem = new TelemetrySystem()

  void setup(){
    injectSysConfig("dd.telemetry.dependency-collection.enabled", "false")
  }

  def cleanup() {
    telemetrySystem?.shutdown()
  }

  void 'installs disabled dependency service and verify transformer'() {
    when:
    def depService = telemetrySystem.createDependencyService(inst)

    then:
    0 * inst.addTransformer(_ as LocationsCollectingTransformer)
    null == depService
  }
}
