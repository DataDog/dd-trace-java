package datadog.trace.api.telemetry;

import static java.util.Collections.emptyIterator;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

public class EndpointCollector {

  private static final EndpointCollector INSTANCE = new EndpointCollector();

  public static EndpointCollector get() {
    return INSTANCE;
  }

  private final AtomicReference<Iterator<Endpoint>> provider =
      new AtomicReference<>(emptyIterator());

  public Iterator<Endpoint> drain() {
    return provider.getAndSet(emptyIterator());
  }

  public void supplier(final Iterator<Endpoint> supplier) {
    provider.set(supplier);
  }
}
