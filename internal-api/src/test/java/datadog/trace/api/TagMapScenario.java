package datadog.trace.api;

public enum TagMapScenario {
  LEGACY_EMPTY(LegacyTagMapFactory.INSTANCE, 0),
  OPTIMIZED_EMPTY(OptimizedTagMapFactory.INSTANCE, 0),
  OPTIMIZED_XSMALL(OptimizedTagMapFactory.INSTANCE, 5),
  OPTIMIZED_SMALL(OptimizedTagMapFactory.INSTANCE, 10),
  OPTIMIZED_MEDIUM(OptimizedTagMapFactory.INSTANCE, 25),
  OPTIMIZED_LARGE(OptimizedTagMapFactory.INSTANCE, 125);

  final TagMapFactory<?> factory;
  final int size;

  TagMapScenario(TagMapFactory<?> factory, int size) {
    this.factory = factory;
    this.size = size;
  }

  public final int size() {
    return this.size;
  }

  public final TagMap create() {
    TagMap map = factory.create();
    for (int i = 0; i < this.size; ++i) {
      map.put("filler-key-" + i, "filler-value-" + i);
    }
    return map;
  }
}
