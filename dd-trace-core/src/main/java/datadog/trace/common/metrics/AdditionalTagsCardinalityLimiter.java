package datadog.trace.common.metrics;

import datadog.trace.core.monitor.HealthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds how many distinct stat entries (aggregate-table entries) with any additional tags we'll
 * admit into a single flush bucket.
 *
 * <p>Single global counter, single-threaded -- the aggregator thread is the sole writer, so a plain
 * {@code int} is sufficient (no {@code AtomicInteger}). The counter goes up by one each time we
 * admit a brand-new {@link AggregateEntry} that includes any non-null additional tag values. When
 * the counter reaches the cap, any further new entries have all their additional tag values
 * replaced with the per-key {@code "<key>:blocked_by_tracer"} sentinel before they become part of
 * the hash + match keys -- so the blocked spans collapse into a small number of "shape" entries
 * rather than into the no-additional-tags base bucket. Spans whose full canonical already exists in
 * the table merge into it regardless of the cap.
 *
 * <p>The counter and the one-shot warn flag reset every flush via {@link #resetBucket()}.
 *
 * <p>Acknowledged spec deviation: the CSS v1.3.0 spec requires per-tag isolation. This is a single
 * global counter for simplicity. A misconfigured tag can starve another tag's admission of new
 * entries within a bucket, but every span still gets emitted with its dimension keys preserved
 * (values masked) and its base stats unchanged.
 */
final class AdditionalTagsCardinalityLimiter {

  private static final Logger log = LoggerFactory.getLogger(AdditionalTagsCardinalityLimiter.class);

  private final int maxStatEntries;
  private final HealthMetrics healthMetrics;
  private int statEntryCounter;
  private boolean warnedAboutCardinality;

  AdditionalTagsCardinalityLimiter(int maxStatEntries, HealthMetrics healthMetrics) {
    this.maxStatEntries = maxStatEntries;
    this.healthMetrics = healthMetrics;
  }

  /** Whether the global stat-entry counter has reached the cap. */
  boolean isAtCap() {
    return statEntryCounter >= maxStatEntries;
  }

  /**
   * Records that a brand-new entry's additional tag values were blocked because the bucket is at
   * the cap. Fires the per-key health metric for each tag that had a value and emits one warn log
   * per bucket regardless of how many entries get blocked.
   */
  void recordCardinalityBlock(AdditionalTagsSchema schema, String[] values) {
    if (!warnedAboutCardinality) {
      warnedAboutCardinality = true;
      log.warn(
          "Additional metric tag stat-entry limit of {} reached for the current bucket; "
              + "replacing tag values with '{}' on any new stat entries until the next flush",
          maxStatEntries,
          AdditionalTagsSchema.BLOCKED_VALUE);
    }
    for (int i = 0; i < values.length; i++) {
      if (values[i] != null) {
        healthMetrics.onAdditionalTagValueCardinalityBlocked(schema.name(i));
      }
    }
  }

  /** Bumps the global stat-entry counter by one. */
  void onNewStatEntryAdmitted() {
    statEntryCounter++;
  }

  /** Zeroes the counter and re-arms the warn flag. Called from {@code Aggregator.report}. */
  void resetBucket() {
    statEntryCounter = 0;
    warnedAboutCardinality = false;
  }
}
