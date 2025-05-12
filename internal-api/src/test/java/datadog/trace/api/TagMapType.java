package datadog.trace.api;

public enum TagMapType {
  OPTIMIZED(new OptimizedTagMapFactory()),
  LEGACY(new LegacyTagMapFactory());

  final TagMapFactory<?> factory;

  TagMapType(TagMapFactory<?> factory) {
    this.factory = factory;
  }

  public final TagMap create() {
    return factory.create();
  }

  public final TagMap empty() {
    return factory.empty();
  }
}
