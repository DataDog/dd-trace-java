package datadog.trace.common.metrics;

import datadog.trace.core.monitor.HealthMetrics;
import org.slf4j.Logger;

/**
 * Shared per-cycle cardinality-block reporting. When a handler blocked one or more values during
 * the just-completed reporting cycle, emit a warn log (with the caller's own logger and message
 * template, so log categories stay per-class) and flush the count to {@link HealthMetrics}.
 *
 * <p>Centralizes the warn + health-metric contract that every cardinality-handler owner ({@link
 * PropertyHandlers}, {@link PeerTagSchema}, {@link AdditionalTagsSchema}) shares; {@code
 * PropertyCardinalityHandler} and {@code TagCardinalityHandler} don't share a common type, so the
 * helper takes the already-extracted primitives rather than the handler.
 */
final class CardinalityBlocks {
  private CardinalityBlocks() {}

  /**
   * @param blocked count returned by the handler's {@code reset()}; a no-op when {@code <= 0}
   * @param warnMessage SLF4J template with a single {@code {}} placeholder for {@code name}
   */
  static void reportIfBlocked(
      Logger log,
      HealthMetrics healthMetrics,
      long blocked,
      String name,
      String[] statsDTag,
      String warnMessage) {
    if (blocked > 0) {
      log.warn(warnMessage, name);
      healthMetrics.onTagCardinalityBlocked(statsDTag, blocked);
    }
  }
}
