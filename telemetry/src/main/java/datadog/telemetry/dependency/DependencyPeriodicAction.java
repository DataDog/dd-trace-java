package datadog.telemetry.dependency;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.trace.api.Config;
import java.util.Collections;

public class DependencyPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  private final DependencyService dependencyService;

  public DependencyPeriodicAction(DependencyService dependencyService) {
    this.dependencyService = dependencyService;
  }

  @Override
  public void doIteration(TelemetryService telService) {
    boolean scaEnabled = Config.get().isAppSecScaEnabled();
    // TODO Could just consume from the original queue instead?
    for (Dependency dep : dependencyService.drainDeterminedDependencies()) {
      if (scaEnabled && dep.reachabilityMetadata == null) {
        // RFC: when SCA is enabled, emit metadata:[] for every dependency to signal that
        // SCA is actively monitoring it. ScaReachabilityPeriodicAction will later re-emit
        // the same dependency with actual CVE metadata when hits are recorded.
        dep = new Dependency(dep.name, dep.version, dep.source, dep.hash, Collections.emptyList());
      }
      telService.addDependency(dep);
    }
  }
}
