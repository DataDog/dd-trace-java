package datadog.trace.api.telemetry;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class ProductChangeCollector {

  private static final ProductChangeCollector INSTANCE = new ProductChangeCollector();
  private final Queue<ProductChange> productChanges = new LinkedBlockingQueue<>();

  private ProductChangeCollector() {}

  public static ProductChangeCollector get() {
    return INSTANCE;
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification =
          "All production callers are agent-owned and do not synchronize on this monitor; locking prevents updates from interleaving with draining.")
  public synchronized void update(final ProductChange productChange) {
    productChanges.offer(productChange);
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification =
          "All production callers are agent-owned and do not synchronize on this monitor; locking prevents updates from interleaving with draining.")
  public synchronized List<ProductChange> drain() {
    if (productChanges.isEmpty()) {
      return Collections.emptyList();
    }

    List<ProductChange> list = new LinkedList<>();

    ProductChange productChange;
    while ((productChange = productChanges.poll()) != null) {
      list.add(productChange);
    }

    return list;
  }
}
