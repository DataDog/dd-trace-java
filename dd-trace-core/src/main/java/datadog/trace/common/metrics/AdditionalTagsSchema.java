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
 * Schema describing the configured span-derived primary tag keys. Built once from {@code
 * Config.getTraceStatsAdditionalTags()} at aggregator construction; the name + handler list isn't
 * replaced at runtime, but per-cycle warn-once state lives here too.
 *
 * <p>Parallels {@link PeerTagSchema} for shape -- a sorted, deduped, capped {@code String[]} of
 * names plus per-name {@link TagCardinalityHandler} -- but lives in {@code SpanSnapshot} and
 * {@link AggregateEntry} alongside, not in place of, the peer-tag schema.
 *
 * <p>Each handler enforces a per-key cardinality cap; this class adds a per-value length cap on
 * top, substituting the handler's {@code "<key>:blocked_by_tracer"} sentinel when either limit is
 * hit. Length-blocks and cardinality-blocks each emit a one-shot warn log per reporting cycle per
 * key and fire {@link HealthMetrics#onTagCardinalityBlocked(String)} per blocked value. The
 * aggregate table's {@code maxAggregates} bound prevents total-entry explosion above and beyond
 * what the per-key caps allow.
 */
final class AdditionalTagsSchema {

  private static final Logger log = LoggerFactory.getLogger(AdditionalTagsSchema.class);

  /**
   * Backend stats pipeline supports a small number of primary tag dimensions (4 by default, up to
   * ~10 for elevated quotas). Configuring more than this is misuse; we drop the overflow at
   * startup.
   */
  static final int MAX_ADDITIONAL_TAG_KEYS = 10;

  /**
   * Maximum length of an additional metric tag value. Caps entry footprint + wire payload from
   * stack-trace / JSON / SQL stuffed into a tag by misconfigured app code.
   */
  static final int MAX_ADDITIONAL_TAG_VALUE_LENGTH = 250;

  /** Singleton empty schema returned when no additional tags are configured. */
  static final AdditionalTagsSchema EMPTY =
      new AdditionalTagsSchema(new String[0], new TagCardinalityHandler[0], HealthMetrics.NO_OP);

  private final String[] names;
  private final TagCardinalityHandler[] handlers;
  private final HealthMetrics healthMetrics;

  // Per-cycle warn-once gating. Set.add(name) returns true exactly the first time per cycle, which
  // is the only time we want to emit the warn log. Cleared in resetCardinalityHandlers.
  private final Set<String> warnedCardinality = new HashSet<>();
  private final Set<String> warnedLength = new HashSet<>();

  private AdditionalTagsSchema(
      String[] names, TagCardinalityHandler[] handlers, HealthMetrics healthMetrics) {
    this.names = names;
    this.handlers = handlers;
    this.healthMetrics = healthMetrics;
  }

  /**
   * Builds a schema from the configured tag keys. Sorts alphabetically (so the hash order matches
   * the spec's requirement), dedupes, and caps at {@link #MAX_ADDITIONAL_TAG_KEYS}. Returns the
   * shared empty schema when {@code configured} is null or empty.
   */
  static AdditionalTagsSchema from(
      Set<String> configured, int cardinalityLimit, HealthMetrics healthMetrics) {
    if (configured == null || configured.isEmpty()) {
      return EMPTY;
    }
    List<String> sorted = new ArrayList<>(configured);
    Collections.sort(sorted);
    if (sorted.size() > MAX_ADDITIONAL_TAG_KEYS) {
      log.warn(
          "Configured additional metric tag keys ({}) exceeds the supported limit of {}; "
              + "dropping extra keys: {}",
          sorted.size(),
          MAX_ADDITIONAL_TAG_KEYS,
          sorted.subList(MAX_ADDITIONAL_TAG_KEYS, sorted.size()));
      sorted = sorted.subList(0, MAX_ADDITIONAL_TAG_KEYS);
    }
    String[] namesArr = sorted.toArray(new String[0]);
    TagCardinalityHandler[] handlers = new TagCardinalityHandler[namesArr.length];
    for (int i = 0; i < namesArr.length; i++) {
      handlers[i] = new TagCardinalityHandler(namesArr[i], cardinalityLimit);
    }
    return new AdditionalTagsSchema(namesArr, handlers, healthMetrics);
  }

  int size() {
    return names.length;
  }

  String name(int i) {
    return names[i];
  }

  /**
   * Canonicalizes the additional-tag value at slot {@code i}. Returns {@link UTF8BytesString#EMPTY}
   * for null inputs and the handler's {@code "<key>:blocked_by_tracer"} sentinel when the value
   * exceeds the length cap or pushes the per-key cardinality budget. Fires {@link
   * HealthMetrics#onTagCardinalityBlocked(String)} on each block; emits a one-shot warn log per
   * cycle per key on each kind of block.
   */
  UTF8BytesString register(int i, String value) {
    TagCardinalityHandler handler = handlers[i];
    String name = names[i];
    if (value != null && value.length() > MAX_ADDITIONAL_TAG_VALUE_LENGTH) {
      healthMetrics.onTagCardinalityBlocked(name);
      if (warnedLength.add(name)) {
        log.warn(
            "Value length of {} exceeded the cap of {} for additional metric tag '{}'; the value"
                + " is reported as 'blocked_by_tracer' until the next reporting cycle",
            value.length(),
            MAX_ADDITIONAL_TAG_VALUE_LENGTH,
            name);
      }
      return handler.blockedSentinel();
    }
    UTF8BytesString result = handler.register(value);
    if (handler.isBlockedResult(result)) {
      healthMetrics.onTagCardinalityBlocked(name);
      if (warnedCardinality.add(name)) {
        log.warn(
            "Cardinality limit reached for additional metric tag '{}'; further values are reported"
                + " as 'blocked_by_tracer' until the next reporting cycle",
            name);
      }
    }
    return result;
  }

  /** Resets every per-key handler's working set and clears the per-cycle warn-once tracking. */
  void resetCardinalityHandlers() {
    for (TagCardinalityHandler handler : handlers) {
      handler.reset();
    }
    warnedCardinality.clear();
    warnedLength.clear();
  }
}
