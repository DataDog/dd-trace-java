package datadog.telemetry.dependency;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;

public class DependencyPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  private final DependencyService dependencyService;

  public DependencyPeriodicAction(DependencyService dependencyService) {
    this.dependencyService = dependencyService;
  }

  @Override
  public void doIteration(TelemetryService telService) {
    // TODO Could just consume from the original queue instead?
    for (Dependency dep : dependencyService.drainDeterminedDependencies()) {
      telService.addDependency(dep);
    }
  }
}
