package datadog.telemetry.endpoint;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.EndpointCollector;
import java.util.Iterator;

public class EndpointPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(final TelemetryService service) {
    for (final Iterator<Endpoint> it = EndpointCollector.get().drain(); it.hasNext(); ) {
      if (!service.addEndpoint(it.next())) {
        break;
      }
    }
  }
}
