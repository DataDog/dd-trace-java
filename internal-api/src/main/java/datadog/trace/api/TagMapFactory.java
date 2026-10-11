package datadog.trace.api;

/**
 * Builds {@link TagMap}s of the one implementation this JVM uses: {@link DenseTagMap} when the
 * dense store is on, {@link HashTagMap} otherwise. Rollout scaffolding, like the earlier
 * legacy/optimized split: the hash-only map stays one flag away until the dense store is proven,
 * and is then folded away.
 *
 * <p>A class, not an interface, and its implementations are only reached through {@link #INSTANCE}:
 * the factory not chosen is loaded (to check the field's type) but never linked, so its methods are
 * never verified and its map class is never loaded. With one {@code TagMap} subclass loaded, class
 * hierarchy analysis devirtualizes every call on a {@code TagMap} without a guard.
 */
abstract class TagMapFactory {
  static final TagMapFactory INSTANCE =
      TagMap.DenseStore.ENABLED ? DenseTagMapFactory.INSTANCE : HashTagMapFactory.INSTANCE;

  /** A fresh mutable map that reads through to {@code parent} (may be null). */
  abstract TagMap create(TagMap parent);

  /**
   * A fresh mutable map that reads through to {@code parent} (may be null), with its dense store
   * presized from {@code hint} (may be null; ignored by the hash-only map).
   */
  abstract TagMap create(TagMap parent, SizingHint hint);

  /** The shared frozen empty map. */
  abstract TagMap empty();
}

final class HashTagMapFactory extends TagMapFactory {
  static final HashTagMapFactory INSTANCE = new HashTagMapFactory();

  private static final TagMap EMPTY = new HashTagMap(new Object[1], 0);

  private HashTagMapFactory() {}

  @Override
  TagMap create(TagMap parent) {
    return new HashTagMap(parent);
  }

  @Override
  TagMap create(TagMap parent, SizingHint hint) {
    return new HashTagMap(parent); // no dense store to size
  }

  @Override
  TagMap empty() {
    return EMPTY;
  }
}

final class DenseTagMapFactory extends TagMapFactory {
  static final DenseTagMapFactory INSTANCE = new DenseTagMapFactory();

  private static final TagMap EMPTY = new DenseTagMap(new Object[1], 0);

  private DenseTagMapFactory() {}

  @Override
  TagMap create(TagMap parent) {
    return new DenseTagMap(parent);
  }

  @Override
  TagMap create(TagMap parent, SizingHint hint) {
    return new DenseTagMap(parent, hint);
  }

  @Override
  TagMap empty() {
    return EMPTY;
  }
}
