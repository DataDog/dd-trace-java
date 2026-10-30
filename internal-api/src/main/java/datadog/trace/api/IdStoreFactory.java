package datadog.trace.api;

/**
 * Builds the {@link IdStore}s of the one implementation this JVM uses, today {@link HashIdStore}.
 *
 * <p>Every store is built through {@link Default#INSTANCE}, never with {@code new}, so a JVM loads
 * one {@code IdStore} subclass and class hierarchy analysis devirtualizes every call on an {@code
 * IdStore} without a guard. A store builds its own copies.
 *
 * <p>No static state here, and no {@link TagMap}, so {@link TagMap#EMPTY} can be built with {@link
 * #createEmpty} whichever of the two classes initializes first.
 */
abstract class IdStoreFactory {
  /** The JVM's store. */
  static final class Default {
    static final IdStoreFactory INSTANCE = HashIdStoreFactory.INSTANCE;

    private Default() {}
  }

  /** A fresh store reading through to {@code parent} (may be null). */
  abstract IdStore create(IdStore parent);

  /** A new empty parentless store, for a frozen map. */
  abstract IdStore createEmpty();
}

final class HashIdStoreFactory extends IdStoreFactory {
  static final HashIdStoreFactory INSTANCE = new HashIdStoreFactory();

  private HashIdStoreFactory() {}

  @Override
  IdStore create(IdStore parent) {
    return new HashIdStore(parent);
  }

  @Override
  IdStore createEmpty() {
    return HashIdStore.createEmpty();
  }
}
