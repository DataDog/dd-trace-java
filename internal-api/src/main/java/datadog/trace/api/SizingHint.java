package datadog.trace.api;

/**
 * Opaque per-operation dense-store sizing hint, and a self-contained {@link
 * datadog.trace.util.FlatHashtable} slot: it carries everything the probe compares ({@link #label}
 * + cached {@link #labelHash}) plus the tuned payload ({@link #size}). Holding key, hash, and value
 * in ONE object behind ONE array slot is deliberate — entry publication is a single reference
 * store, so a reader sees {@code null} or a complete entry (never a torn one), and the {@code
 * final} identity fields are visible even under racy publication (JMM final-field guarantee). That
 * sidesteps the memory-ordering / visibility problems parallel key/hash/value arrays would create,
 * no volatile or atomics.
 *
 * <p>Opaque to everything outside {@code datadog.trace.api}: no public members. {@link TagMap}
 * reads {@link #size} to size a fresh dense store and writes it back (monotonic-max) at a terminal
 * point; {@code SizingHelper} mints and compares by {@link #label}/{@link #labelHash}. Callers only
 * ever hold the reference.
 *
 * <p>{@link #labelHash} is supplied by the helper (a single spread source — {@code
 * FlatHashtable.StringHelper.hash}) so the cached gate always matches the probe hash.
 */
public final class SizingHint {
  // Identity: final => safely published under a racy single-reference store. `label` is the
  // operation
  // name (typically an interned literal, so the `==` fast-path usually hits). `labelHash` is the
  // helper's spread hash, cached to gate `equals` with an int compare during probing.
  final String label;
  final int labelHash;

  // Payload: the tuned dense-store size. Plain racy int, updated monotonic-max — a stale/lost read
  // only mis-sizes an array (over/under-provision), never corrupts tag data, so no synchronization.
  int size;

  // When true, {@code size} is fixed and recordSize won't grow it. For the shared default /
  // overflow
  // hint (a HETEROGENEOUS catch-all for operation-less / over-budget spans): self-tuning it via
  // monotonic-max would converge to the max across unlike sharers and over-provision the lean ones.
  final boolean capped;

  SizingHint(String label, int labelHash, int seedSize) {
    this(label, labelHash, seedSize, false);
  }

  SizingHint(String label, int labelHash, int seedSize, boolean capped) {
    this.label = label;
    this.labelHash = labelHash;
    this.size = seedSize;
    this.capped = capped;
  }
}
