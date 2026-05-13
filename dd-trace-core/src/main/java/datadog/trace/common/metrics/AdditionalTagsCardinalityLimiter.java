package datadog.trace.common.metrics;

import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded per-tag cardinality protection for `additional_metric_tags`.
 *
 * <p>For each configured tag key, admits at most {@code limitPerTag} distinct values within a
 * rolling window. Excess values are replaced with {@link #BLOCKED_VALUE} so the span's base stats
 * still flow through but the extra dimension is suppressed.
 *
 * <p>The rolling window is implemented as a hard reset: callers schedule {@link #reset()} on a
 * fixed interval (10 minutes by default). After a reset, previously blocked values get a fresh
 * chance to be admitted.
 */
final class AdditionalTagsCardinalityLimiter {

  static final String BLOCKED_VALUE = "blocked_by_tracer";

  private static final Logger log = LoggerFactory.getLogger(AdditionalTagsCardinalityLimiter.class);

  private final int limitPerTag;
  private final HealthMetrics healthMetrics;
  private final ConcurrentHashMap<String, Set<String>> seenValuesPerTag = new ConcurrentHashMap<>();
  private final Set<String> warnedAboutCardinality =
      Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final Set<String> warnedAboutLength =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  AdditionalTagsCardinalityLimiter(int limitPerTag, HealthMetrics healthMetrics) {
    this.limitPerTag = limitPerTag;
    this.healthMetrics = healthMetrics;
  }

  /**
   * @return {@code value} if admitted under the cap, otherwise {@link #BLOCKED_VALUE}.
   */
  String admitOrBlock(String tagKey, String value) {
    Set<String> seen =
        seenValuesPerTag.computeIfAbsent(
            tagKey, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
    if (seen.contains(value)) {
      return value;
    }
    if (seen.size() >= limitPerTag) {
      healthMetrics.onAdditionalTagValueCardinalityBlocked(tagKey);
      if (warnedAboutCardinality.add(tagKey)) {
        log.warn(
            "Additional metric tag '{}' exceeded the per-tag cardinality limit of {}; "
                + "replacing values with '{}' for the rest of the current window",
            tagKey,
            limitPerTag,
            BLOCKED_VALUE);
      }
      return BLOCKED_VALUE;
    }
    seen.add(value);
    return value;
  }

  /**
   * Records that a value for {@code tagKey} was blocked due to exceeding the per-value length cap.
   * Fires the same health metric as a cardinality block and emits a distinct warn log line once per
   * tag key per window.
   */
  void noteBlockedDueToLength(String tagKey, int valueLength, int maxLength) {
    healthMetrics.onAdditionalTagValueCardinalityBlocked(tagKey);
    if (warnedAboutLength.add(tagKey)) {
      log.warn(
          "Additional metric tag '{}' had a value of length {} exceeding the max length of {}; "
              + "replacing with '{}' for the rest of the current window",
          tagKey,
          valueLength,
          maxLength,
          BLOCKED_VALUE);
    }
  }

  /** Clears per-tag value sets and rearms the per-key log lines. Invoked by the periodic task. */
  void reset() {
    for (Set<String> seen : seenValuesPerTag.values()) {
      seen.clear();
    }
    warnedAboutCardinality.clear();
    warnedAboutLength.clear();
  }
}
