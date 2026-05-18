package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
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
 * <p>Parallels {@link PeerTagSchema} for shape -- a sorted, deduped, capped {@code String[]} of
 * names plus per-name pre-computed artifacts -- but lives in {@code SpanSnapshot} and {@link
 * AggregateEntry} alongside, not in place of, the peer-tag schema.
 *
 * <p>What's pre-built:
 *
 * <ul>
 *   <li>{@link #names} -- the alphabetically sorted, deduped, capped list of tag keys to extract.
 *   <li>{@link #blockedSentinels} -- one shared {@code UTF8BytesString("<key>:blocked_by_tracer")}
 *       per configured key, used whenever a value is replaced by the length cap or the
 *       global-bucket cardinality cap.
 * </ul>
 */
final class AdditionalTagsSchema {

  /**
   * Backend stats pipeline supports a small number of primary tag dimensions (4 by default, up to
   * ~10 for elevated quotas). Configuring more than this is misuse; we drop the overflow at
   * startup.
   */
  static final int MAX_ADDITIONAL_TAG_KEYS = 10;

  /**
   * Maximum length of an additional metric tag value. Caps entry footprint + wire payload from
   * stack-trace / JSON / SQL stuffed into a tag by misconfigured app code. Values exceeding this
   * are replaced with the per-key {@code "<key>:blocked_by_tracer"} sentinel.
   */
  static final int MAX_ADDITIONAL_TAG_VALUE_LENGTH = 250;

  static final String BLOCKED_VALUE = "blocked_by_tracer";

  /** Singleton empty schema returned when no additional tags are configured. */
  static final AdditionalTagsSchema EMPTY =
      new AdditionalTagsSchema(new String[0], new UTF8BytesString[0]);

  final String[] names;
  final UTF8BytesString[] blockedSentinels;

  private AdditionalTagsSchema(String[] names, UTF8BytesString[] blockedSentinels) {
    this.names = names;
    this.blockedSentinels = blockedSentinels;
  }

  /**
   * Builds a schema from the configured tag keys. Sorts alphabetically (so the hash order matches
   * the spec's requirement), dedupes, and caps at {@link #MAX_ADDITIONAL_TAG_KEYS}. Returns the
   * shared empty schema when {@code configured} is null or empty.
   */
  static AdditionalTagsSchema from(Set<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return EMPTY;
    }
    List<String> sorted = new ArrayList<>(configured);
    Collections.sort(sorted);
    if (sorted.size() > MAX_ADDITIONAL_TAG_KEYS) {
      Logger log = LoggerFactory.getLogger(AdditionalTagsSchema.class);
      log.warn(
          "Configured additional metric tag keys ({}) exceeds the supported limit of {}; "
              + "dropping extra keys: {}",
          sorted.size(),
          MAX_ADDITIONAL_TAG_KEYS,
          sorted.subList(MAX_ADDITIONAL_TAG_KEYS, sorted.size()));
      sorted = sorted.subList(0, MAX_ADDITIONAL_TAG_KEYS);
    }
    String[] namesArr = sorted.toArray(new String[0]);
    UTF8BytesString[] sentinels = new UTF8BytesString[namesArr.length];
    for (int i = 0; i < namesArr.length; i++) {
      sentinels[i] = UTF8BytesString.create(namesArr[i] + ":" + BLOCKED_VALUE);
    }
    return new AdditionalTagsSchema(namesArr, sentinels);
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
}
