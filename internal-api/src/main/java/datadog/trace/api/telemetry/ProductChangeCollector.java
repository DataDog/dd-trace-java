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

  // Claude: SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION: should be reviewed by team.
  // This is a singleton exposed via the static get()/INSTANCE accessor, so any code holding the
  // instance synchronizes on the same monitor that this telemetry path uses. The backing queue is
  // already a LinkedBlockingQueue, so the method-level lock looks redundant and could be dropped or
  // replaced with a private lock.
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification = "Singleton exposed via static accessor; review whether monitor is needed")
  public synchronized void update(final ProductChange productChange) {
    productChanges.offer(productChange);
  }

  // Claude: SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION: should be reviewed by team.
  // This is a singleton exposed via the static get()/INSTANCE accessor, so any code holding the
  // instance synchronizes on the same monitor that this telemetry path uses. The backing queue is
  // already a LinkedBlockingQueue, so the method-level lock looks redundant and could be dropped or
  // replaced with a private lock.
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification = "Singleton exposed via static accessor; review whether monitor is needed")
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
