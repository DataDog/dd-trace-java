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
      final Endpoint endpoint = it.next();
      if (!service.addEndpoint(endpoint)) {
        collector.supplier(new HeadAndTailIterator(endpoint, it)); // try again latter
        break;
      }
    }
  }

  private static final class HeadAndTailIterator implements Iterator<Endpoint> {
    private final Endpoint head;
    private final Iterator<Endpoint> tail;
    private boolean first;

    private HeadAndTailIterator(final Endpoint head, final Iterator<Endpoint> tail) {
      this.head = head;
      this.tail = tail;
      first = true;
    }

    @Override
    public boolean hasNext() {
      return first || tail.hasNext();
    }

    @Override
    public Endpoint next() {
      if (first) {
        first = false;
        return head;
      }
      return tail.next();
    }
  }
}
