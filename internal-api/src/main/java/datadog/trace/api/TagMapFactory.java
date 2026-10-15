package datadog.trace.api;

/**
 * Builds {@link TagMap}s of the one implementation this JVM uses: {@link DenseTagMap} when the
 * dense store is on, {@link HashTagMap} otherwise. Rollout scaffolding, like the earlier
 * legacy/optimized split: the hash-only map stays one flag away until the dense store is proven,
 * and is then folded away.
 *
 * <p>A class, not an interface, and its implementations are only reached through {@link
 * Default#INSTANCE}: the factory not chosen is loaded (to check the field's type) but never linked,
 * so its methods are never verified and its map class is never loaded. With one {@code TagMap}
 * subclass loaded, class hierarchy analysis devirtualizes every call on a {@code TagMap} without a
 * guard.
 *
 * <p>Class initialization must work whichever of TagMap and the factories initializes first: an
 * error in a static initializer poisons that class for the JVM's life, and a read of a class still
 * initializing on the same thread silently sees null. So this class has no static state
 * (initializing a subclass initializes it first, before the subclass's own {@code INSTANCE} is
 * set), and {@link TagMap#EMPTY} is built fresh by {@link #newEmpty} rather than read from a
 * factory's cache.
 */
abstract class TagMapFactory {
  /** The JVM's store, chosen once by the dense-store flag. */
  static final class Default {
    static final TagMapFactory INSTANCE =
        TagMap.DenseStore.ENABLED ? DenseTagMapFactory.INSTANCE : HashTagMapFactory.INSTANCE;
  }

  /** A fresh mutable map that reads through to {@code parent} (may be null). */
  abstract TagMap create(TagMap parent);

  /**
   * A fresh mutable map that reads through to {@code parent} (may be null), with its dense store
   * presized from {@code hint} (may be null; ignored by the hash-only map).
   */
  abstract TagMap create(TagMap parent, SizingHint hint);

  /** A new frozen empty map of this store; {@link TagMap#EMPTY} is built with it. */
  abstract TagMap newEmpty();

  /**
   * The shared frozen empty map: {@link TagMap#EMPTY} for the JVM's store, or one of this store's
   * own for the other (only tests build maps of the store the JVM didn't choose).
   */
  abstract TagMap empty();
}

final class HashTagMapFactory extends TagMapFactory {
  static final HashTagMapFactory INSTANCE = new HashTagMapFactory();

  /** This store's own empty map, when it isn't the JVM's store; see {@link #empty}. */
  static final class OwnEmpty {
    static final TagMap EMPTY = INSTANCE.newEmpty();
  }

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
  TagMap newEmpty() {
    return new HashTagMap(new Object[1], 0);
  }

  @Override
  TagMap empty() {
    return this == Default.INSTANCE ? TagMap.EMPTY : OwnEmpty.EMPTY;
  }
}

final class DenseTagMapFactory extends TagMapFactory {
  static final DenseTagMapFactory INSTANCE = new DenseTagMapFactory();

  /** This store's own empty map, when it isn't the JVM's store; see {@link #empty}. */
  static final class OwnEmpty {
    static final TagMap EMPTY = INSTANCE.newEmpty();
  }

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
  TagMap newEmpty() {
    return new DenseTagMap(new Object[1], 0);
  }

  @Override
  TagMap empty() {
    return this == Default.INSTANCE ? TagMap.EMPTY : OwnEmpty.EMPTY;
  }
}
