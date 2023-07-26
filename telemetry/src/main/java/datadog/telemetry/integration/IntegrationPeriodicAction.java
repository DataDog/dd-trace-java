package datadog.telemetry.integration;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Integration;
import datadog.trace.api.IntegrationsCollector;
import java.util.Map;

public class IntegrationPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(TelemetryService service) {
    Map<String, Boolean> integrations = IntegrationsCollector.get().drain();

    for (Map.Entry<String, Boolean> entry : integrations.entrySet()) {
      // TODO no need to convert anymore
      String name = entry.getKey();
      Boolean enabled = entry.getValue();
      service.addIntegration(new Integration().name(name).enabled(enabled));
    }
  }
}
