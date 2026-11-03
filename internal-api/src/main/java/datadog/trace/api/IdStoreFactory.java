package datadog.trace.api;

/**
 * Builds the {@link IdStore}s of the one implementation this JVM uses, today {@link HashIdStore}.
 *
 * <p>Every store is built through {@link Default#INSTANCE}, never with {@code new}, so a JVM loads
 * one {@code IdStore} subclass and class hierarchy analysis devirtualizes every call on an {@code
 * IdStore} without a guard. So nothing that is always loaded may extend {@code IdStore}. A store
 * builds its own copies.
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

  /**
   * Creates a store for a span or map.
   *
   * @param parent the frozen store to read through to; may be null
   * @return a fresh, empty store
   */
  abstract IdStore create(IdStore parent);

  /**
   * Creates the store for a frozen empty map.
   *
   * @return a new empty parentless store
   */
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
