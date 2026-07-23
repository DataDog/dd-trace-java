package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable schema of configured span-derived primary tag keys; built once at aggregator
 * construction.
 */
final class AdditionalTagsSchema {

  private static final Logger log = LoggerFactory.getLogger(AdditionalTagsSchema.class);

  // Backend stats pipeline only supports a few primary tag dimensions; drop overflow at startup.
  static final int MAX_ADDITIONAL_TAG_KEYS = 4;

  // Health-metric statsD tags per the approved Cardinality Limits RFC (section 5): collapses are
  // reported under the lowercased protobuf field name additional_metric_tags, with cardinality-
  // collapses (collapsed:) and per-value length-collapses (oversized:) tagged distinctly.
  private static final String[] COLLAPSED_STATSD_TAG = {"collapsed:additional_metric_tags"};
  private static final String[] OVERSIZED_STATSD_TAG = {"oversized:additional_metric_tags"};

  /** Singleton empty schema returned when no additional tags are configured. */
  static final AdditionalTagsSchema EMPTY =
      new AdditionalTagsSchema(new String[0], new TagCardinalityHandler[0]);

  final String[] names;

  /** Per-key handlers providing UTF8 caching and per-cycle cardinality limiting. */
  private final TagCardinalityHandler[] handlers;

  private AdditionalTagsSchema(String[] names, TagCardinalityHandler[] handlers) {
    this.names = names;
    this.handlers = handlers;
  }

  /** Test convenience: limits enabled. */
  static AdditionalTagsSchema from(Set<String> configured) {
    return from(configured, MetricCardinalityLimits.ADDITIONAL_TAG_VALUE, true);
  }

  static AdditionalTagsSchema from(Set<String> configured, int limit, boolean useBlockedSentinel) {
    if (configured == null || configured.isEmpty()) {
      return EMPTY;
    }
    List<String> valid = new ArrayList<>();
    for (String key : configured) {
      if (key == null || key.isEmpty()) {
        log.warn("Ignoring empty additional metric tag key");
        continue;
      }
      if (key.contains(":")) {
        log.warn("Ignoring additional metric tag key '{}': keys must not contain ':'", key);
        continue;
      }
      valid.add(key);
    }
    if (valid.isEmpty()) {
      return EMPTY;
    }
    Collections.sort(valid);
    // Keys arrive as a Set, so they are already distinct — no dedup needed.
    if (valid.size() > MAX_ADDITIONAL_TAG_KEYS) {
      log.warn(
          "Configured additional metric tag keys ({}) exceeds the supported limit of {}; "
              + "dropping extra keys: {}",
          valid.size(),
          MAX_ADDITIONAL_TAG_KEYS,
          valid.subList(MAX_ADDITIONAL_TAG_KEYS, valid.size()));
      valid = valid.subList(0, MAX_ADDITIONAL_TAG_KEYS);
    }
    String[] namesArr = valid.toArray(new String[0]);
    TagCardinalityHandler[] handlersArr = new TagCardinalityHandler[namesArr.length];
    for (int i = 0; i < namesArr.length; i++) {
      handlersArr[i] =
          new TagCardinalityHandler(
              namesArr[i],
              limit,
              useBlockedSentinel,
              MetricCardinalityLimits.ADDITIONAL_TAG_MAX_VALUE_LENGTH);
    }
    return new AdditionalTagsSchema(namesArr, handlersArr);
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }

  UTF8BytesString register(int i, String value) {
    return handlers[i].register(value);
  }

  void resetHandlers(HealthMetrics healthMetrics, CardinalityLimitReporter reporter) {
    long totalCollapsed = 0;
    long totalOversized = 0;
    for (int i = 0; i < handlers.length; i++) {
      // oversizedCount() must be read before reset(), which zeroes both per-cycle counts.
      long oversized = handlers[i].oversizedCount();
      long collapsed = handlers[i].reset();
      // The human-facing reporter names the specific tag that triggered the block, so it records
      // both collapse kinds under the tag's own name.
      long blocked = collapsed + oversized;
      if (blocked > 0) {
        reporter.record(names[i], blocked);
      }
      totalCollapsed += collapsed;
      totalOversized += oversized;
    }
    // The health metric is reported at the additional_metric_tags field granularity (not per tag
    // name), with cardinality-collapses and length-collapses ("oversized") tagged distinctly per
    // the approved Cardinality Limits RFC.
    if (totalCollapsed > 0) {
      healthMetrics.onTagCardinalityBlocked(COLLAPSED_STATSD_TAG, totalCollapsed);
    }
    if (totalOversized > 0) {
      healthMetrics.onTagCardinalityBlocked(OVERSIZED_STATSD_TAG, totalOversized);
    }
  }
}
