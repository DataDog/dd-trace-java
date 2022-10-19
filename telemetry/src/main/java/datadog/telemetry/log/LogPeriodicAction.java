package datadog.telemetry.log;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.api.telemetry.TelemetryLogEntry;

public class LogPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(TelemetryService service) {
    for (TelemetryLogEntry entry : LogCollector.get().drain()) {
      service.addLogEntry(entry);
    }
  }
}
