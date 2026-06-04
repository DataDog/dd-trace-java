package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

  // Backend pipeline supports ~4 primary tag dimensions by default; drop overflow at startup.
  static final int MAX_ADDITIONAL_TAG_KEYS = 10;

  /** Singleton empty schema returned when no additional tags are configured. */
  static final AdditionalTagsSchema EMPTY =
      new AdditionalTagsSchema(new String[0], new TagCardinalityHandler[0], HealthMetrics.NO_OP);

  final String[] names;

  /** Per-key handlers providing UTF8 caching and per-cycle cardinality limiting. */
  private final TagCardinalityHandler[] handlers;

  private final HealthMetrics healthMetrics;
  private final Set<String> warnedCardinality = new HashSet<>();

  private AdditionalTagsSchema(
      String[] names, TagCardinalityHandler[] handlers, HealthMetrics healthMetrics) {
    this.names = names;
    this.handlers = handlers;
    this.healthMetrics = healthMetrics;
  }

  /** Test convenience: uses {@link HealthMetrics#NO_OP} and limits enabled. */
  static AdditionalTagsSchema from(Set<String> configured) {
    return from(configured, HealthMetrics.NO_OP, true);
  }

  static AdditionalTagsSchema from(
      Set<String> configured, HealthMetrics healthMetrics, boolean useBlockedSentinel) {
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
    // Dedup (sort brings duplicates adjacent)
    List<String> deduped = new ArrayList<>(valid.size());
    String prev = null;
    for (String key : valid) {
      if (!key.equals(prev)) {
        deduped.add(key);
        prev = key;
      }
    }
    if (deduped.size() > MAX_ADDITIONAL_TAG_KEYS) {
      log.warn(
          "Configured additional metric tag keys ({}) exceeds the supported limit of {}; "
              + "dropping extra keys: {}",
          deduped.size(),
          MAX_ADDITIONAL_TAG_KEYS,
          deduped.subList(MAX_ADDITIONAL_TAG_KEYS, deduped.size()));
      deduped = deduped.subList(0, MAX_ADDITIONAL_TAG_KEYS);
    }
    String[] namesArr = deduped.toArray(new String[0]);
    TagCardinalityHandler[] handlersArr = new TagCardinalityHandler[namesArr.length];
    for (int i = 0; i < namesArr.length; i++) {
      handlersArr[i] =
          new TagCardinalityHandler(
              namesArr[i], MetricCardinalityLimits.ADDITIONAL_TAG_VALUE, useBlockedSentinel);
    }
    return new AdditionalTagsSchema(namesArr, handlersArr, healthMetrics);
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }

  UTF8BytesString register(int i, String value) {
    UTF8BytesString result = handlers[i].register(value);
    if (handlers[i].isBlockedResult(result) && warnedCardinality.add(names[i])) {
      log.warn(
          "Cardinality limit reached for additional metric tag '{}'; further values will be reported as blocked_by_tracer",
          names[i]);
    }
    return result;
  }

  void resetHandlers() {
    for (int i = 0; i < handlers.length; i++) {
      long blocked = handlers[i].reset();
      if (blocked > 0) {
        healthMetrics.onTagCardinalityBlocked(handlers[i].statsDTag(), blocked);
      }
    }
  }
}
