package datadog.trace.api;

/**
 * The two {@link TagMap} stores, for tests that run against both in one JVM. In production the
 * dense-store flag picks one store per JVM; here each value builds maps through its own factory, so
 * a test must create, parent and copy through the type it was given, never through {@link TagMap}'s
 * static factories, which use the JVM's default store.
 */
public enum TagMapType {
  HASH(HashTagMapFactory.INSTANCE),
  DENSE(DenseTagMapFactory.INSTANCE);

  final TagMapFactory factory;

  TagMapType(TagMapFactory factory) {
    this.factory = factory;
  }

  public final TagMap create() {
    return factory.create(null);
  }

  /** {@link TagMap#createFromParent(TagMap)}, through this store. */
  public final TagMap createFromParent(TagMap parent) {
    return TagMap.createFromParent(factory, parent, null);
  }

  /** {@link TagMap#EMPTY} for this store. */
  public final TagMap empty() {
    return factory.empty();
  }
}
