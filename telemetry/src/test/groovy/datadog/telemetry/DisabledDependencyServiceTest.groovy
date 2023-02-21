package datadog.telemetry

import datadog.telemetry.dependency.LocationsCollectingTransformer
import datadog.trace.test.util.DDSpecification

import java.lang.instrument.Instrumentation

class DisabledDependencyServiceTest extends DDSpecification{

  Instrumentation inst = Mock()

  void setup(){
    injectSysConfig("dd.telemetry.dependency-collection.enabled", "false")
  }

  void 'installs disabled dependency service and verify transformer'() {
    when:
    def depService = TelemetrySystem.createDependencyService(inst)

    then:
    0 * inst.addTransformer(_ as LocationsCollectingTransformer)
    null == depService
  }
}
