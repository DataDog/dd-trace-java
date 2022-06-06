package datadog.telemetry.dependency

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.DependencyType
import datadog.trace.test.util.DDSpecification

class DependencyPeriodActionSpecification extends DDSpecification {
  DependencyServiceImpl depService = Mock()
  DependencyPeriodicAction periodicAction = new DependencyPeriodicAction(depService)
  TelemetryService telemetryService = Mock()

  void 'transforms dependencies and pushes them to the telemetry service'() {
    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * depService.determineNewDependencies() >> [new Dependency('name', '1.2.3', 'name-1.2.3.jar', 'DEADBEEF')]
    1 * telemetryService.addDependency({ datadog.telemetry.api.Dependency dep ->
      dep.name == 'name' &&
        dep.version == '1.2.3' &&
        dep.type == DependencyType.PLATFORMSTANDARD &&
        dep.hash == 'DEADBEEF'
    })
    0 * _._
  }
}
