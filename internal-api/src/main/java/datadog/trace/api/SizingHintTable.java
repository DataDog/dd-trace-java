package datadog.trace.api;

import datadog.trace.util.FlatHashtable;
import javax.annotation.Nullable;

/**
 * Process-wide, self-tuning registry of per-operation {@link SizingHint}s, keyed by operation name.
 * Pure static and package-private: the tracer resolves a hint at span build through {@link
 * SizingHint#forOperation} and hands it to the span, which sizes its dense {@link IdStore} from it.
 * The span records its size back at three points: in {@code CoreTracer.write}, for every trace
 * before it is written or dropped (the writer can drop a trace without serializing it); again at
 * serialization ({@code DDSpanContext.processTagsAndBaggage}), with the tags serialization adds;
 * and in {@code DenseIdStore.removeAt}, just before a removal shrinks the store, so its peak isn't
 * lost. The hint keeps the high-water mark, so later spans of that operation size correctly.
 *
 * <p>Only meaningful with the dense store on ({@link IdStoreFactory.Default#DENSE}); with it off,
 * {@link #hintFor} returns {@code null} before touching anything, so the default span path does no
 * lookup and the lane tables are never even allocated (they live in a holder class).
 *
 * <p>Two lanes, because a span's dense size depends systematically on whether it is an <b>entry</b>
 * (local-root) span or not: entry spans carry the trace-metadata / enriching tags and children
 * don't, so the same operation name has two different steady-state sizes. {@link #hintFor} picks
 * the lane.
 *
 * <p><b>Bounded + racy by design.</b> Each lane is a fixed-capacity {@link FlatHashtable.D1} (never
 * resized) so memory is bounded even under unbounded/dynamic operation names; once a lane's
 * cardinality budget is spent, further operations share a capped overflow hint. Insertion and the
 * best-effort-max size update are lock-free and deliberately racy (the table is used from many
 * threads without synchronization): a lost size-counter update lets a lane slightly overshoot its
 * budget (still bounded by the backing array, whose full-table insert just fails), and two threads
 * minting the same operation at once may leave a shadowed, unreachable duplicate. Either only
 * mis-sizes an array (over/under-provision) for a span or two, never corrupts tag data (see {@link
 * FlatHashtable} and {@link SizingHint} for the rationale).
 *
 * <p>Keyed by the operation name's {@code String} form. In practice every operation name we see is
 * a {@code String} or a {@link datadog.trace.bootstrap.instrumentation.api.UTF8BytesString}, whose
 * {@code toString()} just returns its backing field — so the {@code toString()} here is O(1) and
 * allocation-free on the hot path. A {@code null} operation name gets no hint (the span falls back
 * to the generic default capacity); a missed hint is benign, never wrong.
 */
final class SizingHintTable {
  private SizingHintTable() {}

  // Seed for a fresh per-operation hint: 0, i.e. untuned -- presize nothing, so until a finished
  // span records its size the store grows exactly as one without a hint (INIT_CAP, then doubling).
  // A path that never records then costs only the presizing win, never more than having no hint.
  // Not a small positive floor either: the hint only grows, and a store presized below its span's
  // tag count pays extra doublings on every span until the hint is tuned.
  static final int SEED_SIZE = 0;

  // Max distinct operation names per lane that get their own hint before collapsing to the capped
  // overflow hint.
  static final int CARDINALITY_LIMIT = 512;

  // The longest operation name that gets a hint: names are kept for the JVM's life, so their length
  // is bounded as well as their number.
  static final int MAX_OPERATION_NAME_LENGTH = 256;

  /**
   * The lanes, in a holder so they are only allocated once the dense store is on and a span asks
   * for a hint.
   */
  static final class Lanes {
    private Lanes() {}

    // Entry (local-root, enriched) lane and non-entry (child) lane, keyed by operation name.
    static final FlatHashtable.D1<String, SizingHint> ENTRY =
        FlatHashtable.D1.createFixed(CARDINALITY_LIMIT, SizingHint[]::new);
    static final FlatHashtable.D1<String, SizingHint> CHILD =
        FlatHashtable.D1.createFixed(CARDINALITY_LIMIT, SizingHint[]::new);

    // Shared hint each lane returns once its budget is exhausted: untuned (SEED_SIZE) and capped,
    // so it presizes nothing and a store keeps the shared empty arrays until its first write, as
    // without a hint. Capped because it's a heterogeneous catch-all -- growing it would
    // over-provision lean sharers to the max of an unlike cohort.
    static final SizingHint ENTRY_OVERFLOW = new SizingHint(null, SEED_SIZE, /* capped */ true);
    static final SizingHint CHILD_OVERFLOW = new SizingHint(null, SEED_SIZE, /* capped */ true);
  }

  /**
   * The sizing hint for {@code operationName} in the given lane: the existing one, a freshly-minted
   * (seeded) one if the lane has budget, or the shared capped overflow hint if it's full. Returns
   * {@code null} when the dense store is off (constant-folded, so the default span path does no
   * lookup), for a {@code null} operation name, and for one longer than {@link
   * #MAX_OPERATION_NAME_LENGTH} — the span then uses the generic default capacity (operation-less
   * spans aren't reliably similar, and long names aren't worth keeping). Hits are a single probe;
   * the create path is warmup-rare.
   */
  @Nullable
  static SizingHint hintFor(@Nullable CharSequence operationName, boolean entrySpan) {
    if (!IdStoreFactory.Default.DENSE) {
      return null;
    }
    if (operationName == null || operationName.length() > MAX_OPERATION_NAME_LENGTH) {
      return null;
    }
    // O(1) for String / UTF8BytesString (see class doc)
    final String key = operationName.toString();
    final FlatHashtable.D1<String, SizingHint> lane = entrySpan ? Lanes.ENTRY : Lanes.CHILD;
    // One probe: returns a hit directly, and null only when the fixed lane is full and the key is
    // absent.
    final SizingHint hint = lane.tryGetOrCreateOrNull(key, SizingHint::new);
    if (hint != null) {
      return hint;
    }
    return entrySpan ? Lanes.ENTRY_OVERFLOW : Lanes.CHILD_OVERFLOW;
  }
}
