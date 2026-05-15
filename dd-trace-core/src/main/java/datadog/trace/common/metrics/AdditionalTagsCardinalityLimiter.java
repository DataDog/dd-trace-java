package datadog.trace.common.metrics;

import datadog.trace.core.monitor.HealthMetrics;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds how many distinct stat entries (MetricKeys) with any additional tags we'll let into a
 * single flush bucket, and how long a single tag value can be.
 *
 * <p>One global counter. It goes up by one each time we add a brand-new MetricKey to the bucket
 * that includes any additional tags. When the counter reaches the cap, any further <em>new</em>
 * MetricKeys drop all of their additional tags. Spans whose full MetricKey already exists in the
 * bucket are unaffected — they keep merging into the existing entry.
 *
 * <p>The counter and both one-shot warn flags reset every flush via {@link #resetBucket()}.
 */
final class AdditionalTagsCardinalityLimiter {

  static final String BLOCKED_VALUE = "blocked_by_tracer";
  static final int MAX_ADDITIONAL_TAG_VALUE_LENGTH = 250;

  private static final Logger log = LoggerFactory.getLogger(AdditionalTagsCardinalityLimiter.class);

  private final int maxStatEntries;
  private final HealthMetrics healthMetrics;
  private final AtomicInteger statEntryCounter = new AtomicInteger();
  private volatile boolean warnedAboutCardinality;
  private volatile boolean warnedAboutLength;

  AdditionalTagsCardinalityLimiter(int maxStatEntries, HealthMetrics healthMetrics) {
    this.maxStatEntries = maxStatEntries;
    this.healthMetrics = healthMetrics;
  }

  /**
   * Returns {@code value} unchanged if it's short enough, or {@link #BLOCKED_VALUE} if it's longer
   * than {@link #MAX_ADDITIONAL_TAG_VALUE_LENGTH}. Fires the health metric on every block and emits
   * one warn log per bucket regardless of which tag triggered it.
   */
  String applyLengthCap(String tagKey, String value) {
    if (value.length() > MAX_ADDITIONAL_TAG_VALUE_LENGTH) {
      healthMetrics.onAdditionalTagValueCardinalityBlocked(tagKey);
      if (!warnedAboutLength) {
        synchronized (this) {
          if (!warnedAboutLength) {
            warnedAboutLength = true;
            log.warn(
                "Additional metric tag '{}' had a value of length {} exceeding the max length of {}; "
                    + "replacing with '{}' for the rest of the current bucket",
                tagKey,
                value.length(),
                MAX_ADDITIONAL_TAG_VALUE_LENGTH,
                BLOCKED_VALUE);
          }
        }
      }
      return BLOCKED_VALUE;
    }
    return value;
  }

  /**
   * Returns true if the global stat-entry counter has reached the cap. Read-only; no side effects.
   */
  boolean isAtCap() {
    return statEntryCounter.get() >= maxStatEntries;
  }

  /**
   * Records that a span's additional tags were dropped because the bucket is at the cap. Emits one
   * warn log per bucket regardless of how many spans get blocked.
   */
  void recordCardinalityBlock() {
    if (!warnedAboutCardinality) {
      synchronized (this) {
        if (!warnedAboutCardinality) {
          warnedAboutCardinality = true;
          log.warn(
              "Additional metric tag stat-entry limit of {} reached for the current bucket; "
                  + "dropping additional tags from any new stat entries until the next flush",
              maxStatEntries);
        }
      }
    }
  }

  /**
   * Bumps the global stat-entry counter. Called once per brand-new MetricKey we admit that includes
   * any additional tags.
   */
  void onNewStatEntryAdmitted() {
    statEntryCounter.incrementAndGet();
  }

  /**
   * Zeroes the counter and re-arms both warn flags. Called whenever the metrics aggregator flushes
   * a bucket so the next bucket starts with a fresh budget.
   */
  void resetBucket() {
    statEntryCounter.set(0);
    warnedAboutCardinality = false;
    warnedAboutLength = false;
  }
}
