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
 * Immutable schema describing the configured span-derived primary tag keys. Built once from {@code
 * Config.getTraceStatsAdditionalTags()} at aggregator construction; not replaced at runtime.
 *
 * <p>Parallels {@link PeerTagSchema} for shape: a sorted, deduped, validated, capped {@code
 * String[]} of names plus per-name {@link TagCardinalityHandler}s for UTF8 caching and value-level
 * cardinality limiting. The handlers are reset each reporting cycle via {@link #resetHandlers()}.
 *
 * <p>What's pre-built:
 *
 * <ul>
 *   <li>{@link #names} -- the alphabetically sorted, deduped, validated, capped list of tag keys.
 *   <li>{@link #blockedSentinels} -- one shared {@code UTF8BytesString("<key>:blocked_by_tracer")}
 *       per configured key, returned when the per-tag cardinality budget is exhausted.
 *   <li>{@link #handlers} -- one {@link TagCardinalityHandler} per key providing UTF8 reuse and
 *       per-cycle cardinality limiting. Aggregator-thread-only; reset each cycle.
 * </ul>
 */
final class AdditionalTagsSchema {

  private static final Logger log = LoggerFactory.getLogger(AdditionalTagsSchema.class);

  /**
   * Backend stats pipeline supports a small number of primary tag dimensions (4 by default, up to
   * ~10 for elevated quotas). Configuring more than this is misuse; we drop the overflow at
   * startup.
   */
  static final int MAX_ADDITIONAL_TAG_KEYS = 10;

  static final String BLOCKED_VALUE = "blocked_by_tracer";

  /** Singleton empty schema returned when no additional tags are configured. */
  static final AdditionalTagsSchema EMPTY =
      new AdditionalTagsSchema(
          new String[0], new UTF8BytesString[0], new TagCardinalityHandler[0], HealthMetrics.NO_OP);

  final String[] names;
  final UTF8BytesString[] blockedSentinels;

  /** Per-key handlers providing UTF8 caching and per-cycle cardinality limiting. */
  private final TagCardinalityHandler[] handlers;

  private final HealthMetrics healthMetrics;

  private AdditionalTagsSchema(
      String[] names,
      UTF8BytesString[] blockedSentinels,
      TagCardinalityHandler[] handlers,
      HealthMetrics healthMetrics) {
    this.names = names;
    this.blockedSentinels = blockedSentinels;
    this.handlers = handlers;
    this.healthMetrics = healthMetrics;
  }

  /** Test convenience: uses {@link HealthMetrics#NO_OP}. */
  static AdditionalTagsSchema from(Set<String> configured) {
    return from(configured, HealthMetrics.NO_OP, AggregateEntry.LIMITS_ENABLED);
  }

  static AdditionalTagsSchema from(Set<String> configured, HealthMetrics healthMetrics) {
    return from(configured, healthMetrics, AggregateEntry.LIMITS_ENABLED);
  }

  /**
   * Builds a schema from the configured tag keys. Validates each key (non-empty, no {@code :}),
   * sorts alphabetically, dedupes, and caps at {@link #MAX_ADDITIONAL_TAG_KEYS}. Returns the shared
   * empty schema when {@code configured} is null or empty.
   */
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
    UTF8BytesString[] sentinels = new UTF8BytesString[namesArr.length];
    TagCardinalityHandler[] handlersArr = new TagCardinalityHandler[namesArr.length];
    for (int i = 0; i < namesArr.length; i++) {
      sentinels[i] = UTF8BytesString.create(namesArr[i] + ":" + BLOCKED_VALUE);
      handlersArr[i] =
          new TagCardinalityHandler(
              namesArr[i], MetricCardinalityLimits.ADDITIONAL_TAG_VALUE, useBlockedSentinel);
    }
    return new AdditionalTagsSchema(namesArr, sentinels, handlersArr, healthMetrics);
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }

  UTF8BytesString blockedSentinel(int i) {
    return blockedSentinels[i];
  }

  /**
   * Canonicalizes {@code value} for the additional tag at slot {@code i} through the per-key {@link
   * TagCardinalityHandler}: provides UTF8 caching and returns the per-tag blocked sentinel when the
   * per-cycle budget is exhausted. Returns {@link UTF8BytesString#EMPTY} for null inputs.
   */
  UTF8BytesString register(int i, String value) {
    return handlers[i].register(value);
  }

  /**
   * Resets every handler's working set and flushes accumulated block counts to {@link
   * HealthMetrics}. Must be called on the aggregator thread each cycle.
   */
  void resetHandlers() {
    for (int i = 0; i < handlers.length; i++) {
      long blocked = handlers[i].reset();
      if (blocked > 0) {
        healthMetrics.onTagCardinalityBlocked(names[i], blocked);
      }
    }
  }
}
