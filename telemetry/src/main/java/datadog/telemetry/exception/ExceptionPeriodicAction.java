package datadog.telemetry.exception;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.KeyValue;
import datadog.trace.api.ExceptionsCollector;
import java.util.Map;

public class ExceptionPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(TelemetryService service) {
    Map<String, String> exceptions = ExceptionsCollector.get().drain();

    for (Map.Entry<String, String> entry : exceptions.entrySet()) {
      String exceptionMsg = entry.getKey();
      String stackTrace = entry.getValue();
      // service.addIntegration(new Integration().name(name).enabled(enabled));
      service.addException(new KeyValue().name(exceptionMsg).value(stackTrace));
    }
  }
}

