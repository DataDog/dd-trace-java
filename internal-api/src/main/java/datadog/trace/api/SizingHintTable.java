package datadog.trace.api;

import datadog.trace.util.FlatHashtable;

/**
 * Process-wide, self-tuning registry of per-operation {@link SizingHint}s, keyed by operation name.
 * Pure static: the tracer resolves a hint here at span build and hands it to the span, which sizes
 * its dense {@link TagMap} from it and records the actual size back on finish — the hint converges
 * to the operation's real known-tag high-water mark, so later spans of that operation size
 * correctly.
 *
 * <p>Two lanes, because a span's dense size depends systematically on whether it is an <b>entry</b>
 * (local-root) span or not: entry spans carry the trace-metadata / enriching tags and children
 * don't, so the same operation name has two different steady-state sizes. {@link #hintFor} picks
 * the lane.
 *
 * <p><b>Bounded + racy by design.</b> Each lane is a fixed-capacity {@link FlatHashtable} (never
 * resized) so memory is bounded even under unbounded/dynamic operation names; once a lane's
 * cardinality budget is spent, further operations share a capped default hint. Construction,
 * insertion, and the monotonic-max size update are all lock-free and deliberately racy — a lost
 * update or a double-mint only mis-sizes an array (over/under-provision) for a span or two, never
 * corrupts tag data (see {@link FlatHashtable} and {@link SizingHint} for the rationale).
 *
 * <p>Keyed by the operation name's {@code String} form. In practice every operation name we see is
 * a {@code String} or a {@link datadog.trace.bootstrap.instrumentation.api.UTF8BytesString}, whose
 * {@code toString()} just returns its backing field — so the {@code toString()} here is O(1) and
 * allocation-free on the hot path. A {@code null} operation name gets no hint (the span falls back
 * to the generic default capacity); a missed hint is benign, never wrong.
 */
public final class SizingHintTable {
  private SizingHintTable() {}

  // Seed for a fresh per-operation hint: a floor that self-tunes up via monotonic-max recordSize.
  static final int SEED_SIZE = 1;
  // Fixed size for the shared over-budget hint: a small lean default. Capped (never grown by
  // recordSize) because it's a heterogeneous catch-all -- growing it would over-provision lean
  // sharers
  // to the max of an unlike cohort.
  static final int OVERFLOW_SEED = 8;
  // Max distinct operation names per lane that get their own hint before collapsing to the capped
  // default. Backing capacity is the next power of two >= 2 * this (load factor <= 0.5).
  private static final int CARDINALITY_LIMIT = 512;

  // Concrete-typed static-final singleton => FlatHashtable calls specialize at this call site.
  private static final SizingHelper HELPER = new SizingHelper();

  // Entry (local-root, enriched) lane and non-entry (child) lane, keyed by operation name.
  private static final SizingHint[] ENTRY_SLOTS =
      FlatHashtable.create(SizingHint.class, CARDINALITY_LIMIT);
  private static final SizingHint[] CHILD_SLOTS =
      FlatHashtable.create(SizingHint.class, CARDINALITY_LIMIT);

  // Shared capped hint each lane returns once its budget is exhausted.
  private static final SizingHint ENTRY_OVERFLOW =
      new SizingHint(null, 0, OVERFLOW_SEED, /* capped */ true);
  private static final SizingHint CHILD_OVERFLOW =
      new SizingHint(null, 0, OVERFLOW_SEED, /* capped */ true);

  // Approximate live counts gating each lane's budget. Plain racy ints -- a few over/under the cap
  // under contention is harmless (the cap is a safety bound, not an exact quota).
  private static int entrySize;
  private static int childSize;

  /**
   * The sizing hint for {@code operationName} in the given lane: the existing one, a freshly-minted
   * (seeded) one if the lane has budget, or the shared capped default if it's full. Returns {@code
   * null} for a {@code null} operation name — the span then uses the generic default capacity
   * (operation-less spans aren't reliably similar, so they get no hint). Hits are a single probe;
   * the create path is warmup-rare.
   */
  public static SizingHint hintFor(CharSequence operationName, boolean entrySpan) {
    if (operationName == null) {
      return null;
    }
    final String key =
        operationName.toString(); // O(1) for String / UTF8BytesString (see class doc)
    final SizingHint[] slots = entrySpan ? ENTRY_SLOTS : CHILD_SLOTS;
    final SizingHint overflow = entrySpan ? ENTRY_OVERFLOW : CHILD_OVERFLOW;

    final SizingHint existing = FlatHashtable.get(slots, key, HELPER);
    if (existing != null) {
      return existing;
    }
    if ((entrySpan ? entrySize : childSize) >= CARDINALITY_LIMIT) {
      return overflow;
    }
    final SizingHint created = FlatHashtable.getOrCreate(slots, key, HELPER);
    if (created == null) {
      return overflow; // physically full -- shouldn't happen under the cap, but stay safe
    }
    if (entrySpan) {
      entrySize++; // racy approximate count
    } else {
      childSize++;
    }
    return created;
  }
}
