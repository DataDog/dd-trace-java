package datadog.trace.api;

import datadog.trace.util.FlatHashtable;

/**
 * Opaque per-operation dense-store sizing hint, and a self-contained {@link FlatHashtable.D1}
 * entry: it carries everything the probe compares (the operation name as the {@link
 * FlatHashtable.D1.Entry} key, plus the cached hash from {@link FlatHashtable.Entry#hash}) and the
 * tuned payload ({@link #size}). Holding key, hash, and value in ONE object behind ONE array slot
 * is deliberate — entry publication is a single reference store, so a reader sees {@code null} or a
 * complete entry (never a torn one), and the {@code final} identity fields are visible even under
 * racy publication (JMM final-field guarantee). That sidesteps the memory-ordering / visibility
 * problems parallel key/hash/value arrays would create, no volatile or atomics.
 *
 * <p>Opaque to everything outside {@code datadog.trace.api}: no public members. {@link TagMap}
 * reads {@link #size} to size a fresh dense store and writes it back (best-effort max, see {@link
 * TagMap#recordSize}) at a terminal point; {@link SizingHintTable} mints them. Callers only ever
 * hold the reference.
 */
public final class SizingHint extends FlatHashtable.D1.Entry<String> {
  // Payload: the tuned dense-store size. Plain racy int, updated best-effort max (see
  // TagMap#recordSize -- not strictly monotonic under concurrent finishes). A stale/lost/lowered
  // read only mis-sizes an array (over/under-provision), never corrupts tag data, so no
  // synchronization. Not safely published by a racing create either (FlatHashtable class doc): a
  // reader may see the default 0, which TagMap treats as "no presize" -- benign.
  int size;

  // When true, size is fixed and recordSize won't grow it. For the shared overflow hint (a
  // HETEROGENEOUS catch-all for over-budget operations): self-tuning it toward the observed max
  // would converge to the max across unlike sharers and over-provision the lean ones.
  final boolean capped;

  /**
   * A fresh, uncapped hint for {@code operationName}, seeded at {@link SizingHintTable#SEED_SIZE}.
   */
  SizingHint(String operationName) {
    this(operationName, SizingHintTable.SEED_SIZE, false);
  }

  SizingHint(String operationName, int seedSize, boolean capped) {
    super(operationName);
    this.size = seedSize;
    this.capped = capped;
  }
}
