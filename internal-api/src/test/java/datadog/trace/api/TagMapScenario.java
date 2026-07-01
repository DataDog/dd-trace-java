package datadog.trace.api;

public enum TagMapScenario {
  OPTIMIZED_EMPTY(0),
  OPTIMIZED_XSMALL(5),
  OPTIMIZED_SMALL(10),
  OPTIMIZED_MEDIUM(25),
  OPTIMIZED_LARGE(125);

  final int size;

  TagMapScenario(int size) {
    this.size = size;
  }

  public final int size() {
    return this.size;
  }

  public final TagMap create() {
    TagMap map = TagMap.create();
    for (int i = 0; i < this.size; ++i) {
      map.put("filler-key-" + i, "filler-value-" + i);
    }
    return map;
  }
}
