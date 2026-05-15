package datadog.telemetry.dependency

import datadog.telemetry.TelemetryService
import datadog.trace.test.util.DDSpecification

class DependencyPeriodActionSpecification extends DDSpecification {
  DependencyService depService = Mock()
  DependencyPeriodicAction periodicAction = new DependencyPeriodicAction(depService)
  TelemetryService telemetryService = Mock()

  void 'transforms dependencies and pushes them to the telemetry service (SCA disabled)'() {
    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * depService.drainDeterminedDependencies() >> [new Dependency('name', '1.2.3', 'name-1.2.3.jar', 'DEADBEEF')]
    1 * telemetryService.addDependency({ Dependency dep ->
      dep.name == 'name' &&
        dep.version == '1.2.3' &&
        dep.hash == 'DEADBEEF' &&
        dep.reachabilityMetadata == null
    })
    0 * _._
  }

  void 'adds metadata:[] to all dependencies when SCA is enabled'() {
    setup:
    injectSysConfig('dd.appsec.sca.enabled', 'true')

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * depService.drainDeterminedDependencies() >> [new Dependency('name', '1.2.3', 'name-1.2.3.jar', 'DEADBEEF')]
    1 * telemetryService.addDependency({ Dependency dep ->
      dep.name == 'name' &&
        dep.version == '1.2.3' &&
        dep.reachabilityMetadata != null &&
        dep.reachabilityMetadata.isEmpty()
    })
    0 * _._
  }
}
