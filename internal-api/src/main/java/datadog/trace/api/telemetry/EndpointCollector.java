package datadog.trace.api.telemetry;

import static java.util.Collections.emptyIterator;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

@SuppressFBWarnings(
    value = "SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR",
    justification = "Usage in tests")
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
