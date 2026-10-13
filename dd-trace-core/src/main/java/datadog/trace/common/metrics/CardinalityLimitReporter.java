package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MINUTES;

import datadog.logging.RatelimitedLogger;
import datadog.trace.util.Hashtable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates cardinality-block counts by tag name across reporting cycles and emits a single
 * rate-limited summary, so a tag that stays over its limit no longer warns on every reporting cycle
 * (default every 10s). The durable per-cycle counts still flow to {@link
 * datadog.trace.core.monitor.HealthMetrics#onTagCardinalityBlocked} at each reset site; this
 * reporter is only the human-facing log sink for the same counts.
 *
 * <p>Counts are keyed by tag name here rather than held on the {@link TagCardinalityHandler}s so a
 * peer-tag schema rebuild (a feature-discovery/config update) does not lose them: each reset
 * flushes the cycle's delta here by name before the outgoing handler can be discarded, and a
 * surviving tag's replacement handler simply keeps adding to the same entry. So the config-update
 * transition needs no per-tag transfer or handler reuse -- the count has already left the handler.
 *
 * <p>The backing store is a {@link Hashtable.D1} counter table: each tag's count is mutated in
 * place with no per-update allocation. (FlatHashtable would be a better fit still, but is not yet
 * merged.)
 *
 * <p>Only touched from the aggregator thread; no synchronization.
 */
final class CardinalityLimitReporter {

  private static final Logger log = LoggerFactory.getLogger(CardinalityLimitReporter.class);

  // Distinct blocked tag names in a window: 9 property fields + the configured peer tags + up to
  // AdditionalTagsSchema.MAX_ADDITIONAL_TAG_KEYS + base.service, with headroom for the brief
  // overlap
  // of old and new peer names across a schema rebuild. Fixed capacity; the table chains on overflow
  // rather than dropping, so an underestimate only adds chain depth on this cold path.
  private static final int TAG_CAPACITY = 64;

  // Rough width of one "<tag>=<count>, " entry, used to pre-size the summary builder. Cold path, so
  // an over-estimate just avoids a resize rather than mattering for footprint.
  private static final int APPROX_CHARS_PER_ENTRY = 32;

  private final RatelimitedLogger rlLog;
  // Tag name -> blocked count accumulated since the last emitted summary.
  private final Hashtable.D1<String, TagBlockEntry> blockedByTag = new Hashtable.D1<>(TAG_CAPACITY);

  CardinalityLimitReporter() {
    this(new RatelimitedLogger(log, 5, MINUTES));
  }

  CardinalityLimitReporter(RatelimitedLogger rlLog) {
    this.rlLog = rlLog;
  }

  /** Records {@code count} values blocked for {@code tag} in the current reporting cycle. */
  void record(String tag, long count) {
    if (count > 0) {
      blockedByTag.getOrCreate(tag, TagBlockEntry::new).count += count;
    }
  }

  /**
   * Emits one rate-limited summary of everything blocked since the last emitted line. While the
   * rate limiter suppresses, counts keep accumulating; they are cleared only once a line is
   * actually logged (keyed off {@link RatelimitedLogger#warn}'s return), so each summary reflects
   * the whole period and nothing is dropped in between.
   */
  void reportIfDue() {
    if (blockedByTag.size() == 0) {
      return;
    }
    if (rlLog.warn(
        "Metric tag cardinality limits reached; excess values reported as tracer_blocked_value."
            + " Blocked value counts by tag: {}",
        summarize())) {
      blockedByTag.clear();
    }
  }

  private String summarize() {
    StringBuilder builder = new StringBuilder(blockedByTag.size() * APPROX_CHARS_PER_ENTRY);
    // Non-capturing: the builder is threaded through as forEach's context argument.
    blockedByTag.forEach(
        builder,
        (into, entry) -> {
          if (into.length() > 0) {
            into.append(", ");
          }
          into.append(entry.key()).append('=').append(entry.count);
        });
    return builder.toString();
  }

  /**
   * Single-key counter entry: the tag name (via {@link #key()}) plus its in-place-mutated count.
   */
  private static final class TagBlockEntry extends Hashtable.D1.Entry<String> {
    long count;

    TagBlockEntry(String tag) {
      super(tag);
    }
  }
}
