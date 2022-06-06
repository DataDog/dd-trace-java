package datadog.telemetry.dependency;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.DependencyType;
import java.util.Collection;

public class DependencyPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  private final DependencyService dependencyService;

  public DependencyPeriodicAction(DependencyService dependencyService) {
    this.dependencyService = dependencyService;
  }

  @Override
  public void doIteration(TelemetryService telService) {
    Collection<Dependency> dependencies = dependencyService.determineNewDependencies();
    for (Dependency dep : dependencies) {
      datadog.telemetry.api.Dependency telDep = new datadog.telemetry.api.Dependency();
      telDep.setHash(dep.getHash());
      telDep.setName(dep.getName());
      telDep.setVersion(dep.getVersion());
      telDep.setType(DependencyType.PLATFORMSTANDARD);
      telService.addDependency(telDep);
    }
  }
}
