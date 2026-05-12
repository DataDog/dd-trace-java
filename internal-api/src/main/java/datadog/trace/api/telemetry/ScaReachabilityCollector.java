package datadog.trace.api.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Singleton queue bridging the SCA reachability transformer (appsec module) and the periodic
 * telemetry action (telemetry module) without creating a circular dependency.
 *
 * <p>Pattern mirrors {@code WafMetricCollector}: both modules depend on {@code internal-api}, which
 * owns this class. The transformer enqueues hits; the periodic action drains them.
 */
public final class ScaReachabilityCollector {

  public static final ScaReachabilityCollector INSTANCE = new ScaReachabilityCollector();

  private final BlockingQueue<ScaReachabilityHit> hits = new LinkedBlockingQueue<>();

  private ScaReachabilityCollector() {}

  /** Called by {@code ScaReachabilityTransformer} when a vulnerable class is detected. */
  public void addHit(ScaReachabilityHit hit) {
    hits.offer(hit);
  }

  /**
   * Called by {@code ScaReachabilityPeriodicAction} on each telemetry heartbeat. Drains and returns
   * all pending hits.
   */
  public List<ScaReachabilityHit> drain() {
    if (hits.isEmpty()) {
      return Collections.emptyList();
    }
    List<ScaReachabilityHit> result = new ArrayList<>(hits.size());
    hits.drainTo(result);
    return result;
  }
}
