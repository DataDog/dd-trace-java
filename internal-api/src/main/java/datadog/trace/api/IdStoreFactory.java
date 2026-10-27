package datadog.trace.api;

import javax.annotation.Nullable;

/**
 * Builds the {@link IdStore}s of the one implementation this JVM uses: {@link DenseIdStore} when
 * the dense store is on, {@link HashIdStore} otherwise. Rollout scaffolding: the hash store stays
 * one flag away until the dense store is proven.
 *
 * <p>Every store is built through {@link Default#INSTANCE}, never with {@code new}. The factory not
 * chosen is loaded (to check the field's type) but never linked, so its store class is never
 * loaded; with one {@code IdStore} subclass loaded, class hierarchy analysis devirtualizes every
 * call on an {@code IdStore} without a guard. A store builds its own copies.
 *
 * <p>No static state here, and no {@link TagMap}, so {@link TagMap#EMPTY} can be built with {@link
 * #createEmpty} whichever of the two classes initializes first.
 */
abstract class IdStoreFactory {
  /** The JVM's store, chosen once by the dense-store flag. */
  static final class Default {
    /** Whether the dense store is on; folds wherever it is tested. */
    static final boolean DENSE = Config.get().isTraceDenseTagsEnabled();

    static final IdStoreFactory INSTANCE =
        DENSE ? DenseIdStoreFactory.INSTANCE : HashIdStoreFactory.INSTANCE;

    private Default() {}
  }

  /** A fresh store reading through to {@code parent} (may be null). */
  final IdStore create(IdStore parent) {
    return create(parent, null);
  }

  /**
   * A fresh store reading through to {@code parent} (may be null), presized from {@code hint} where
   * the store keeps sized arrays; a null hint presizes nothing.
   */
  abstract IdStore create(IdStore parent, @Nullable SizingHint hint);

  /** A new empty parentless store, for a frozen map. */
  abstract IdStore createEmpty();
}

final class HashIdStoreFactory extends IdStoreFactory {
  static final HashIdStoreFactory INSTANCE = new HashIdStoreFactory();

  private HashIdStoreFactory() {}

  @Override
  IdStore create(IdStore parent, @Nullable SizingHint hint) {
    return new HashIdStore(parent); // buckets aren't sized per span; a hint is only for dense
  }

  @Override
  IdStore createEmpty() {
    return HashIdStore.createEmpty();
  }
}

final class DenseIdStoreFactory extends IdStoreFactory {
  static final DenseIdStoreFactory INSTANCE = new DenseIdStoreFactory();

  private DenseIdStoreFactory() {}

  @Override
  IdStore create(IdStore parent, @Nullable SizingHint hint) {
    return new DenseIdStore(parent, hint);
  }

  @Override
  IdStore createEmpty() {
    return new DenseIdStore(null);
  }
}
