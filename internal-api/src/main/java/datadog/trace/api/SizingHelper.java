package datadog.trace.api;

import datadog.trace.util.FlatHashtable;

/**
 * {@link FlatHashtable} policy for the per-operation {@link SizingHint} table: keys by operation
 * name, entries are {@code SizingHint}s carrying that name plus its cached spread hash. Stateless —
 * held by {@link SizingHintTable} as a concrete-typed {@code static final} singleton so {@code
 * FlatHashtable.get}/{@code getOrCreate} specialize (devirtualize + inline) at the call site.
 *
 * <p>Extends {@link FlatHashtable.StringHelper}, which seals the spread {@code hash}; this class
 * only supplies {@code matches} and {@code create}. Both use the inherited {@code hash} so the
 * cached {@link SizingHint#labelHash} is always the same spread the probe used.
 */
final class SizingHelper extends FlatHashtable.StringHelper<SizingHint> {
  @Override
  public boolean matches(String key, SizingHint value) {
    // int gate on the cached hash before equals; op-names are usually interned literals, so `==` is
    // the common hit.
    return value.labelHash == hash(key) && (key == value.label || key.equals(value.label));
  }

  @Override
  public SizingHint create(String key) {
    return new SizingHint(key, hash(key), SizingHintTable.SEED_SIZE);
  }
}
