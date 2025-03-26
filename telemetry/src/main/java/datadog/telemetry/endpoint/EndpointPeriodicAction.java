package datadog.telemetry.endpoint;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.EndpointCollector;
import java.util.Iterator;

public class EndpointPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  private final EndpointCollector collector;

  public EndpointPeriodicAction() {
    this(EndpointCollector.get());
  }

  public EndpointPeriodicAction(EndpointCollector collector) {
    this.collector = collector;
  }

  @Override
  public void doIteration(final TelemetryService service) {
    for (final Iterator<Endpoint> it = collector.drain(); it.hasNext(); ) {
      if (!service.addEndpoint(it.next())) {
        break;
      }
    }
  }
}
