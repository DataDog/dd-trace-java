package datadog.trace.api;

public enum TagMapTypePair {
  BOTH_OPTIMIZED(TagMapType.OPTIMIZED, TagMapType.OPTIMIZED),
  BOTH_LEGACY(TagMapType.LEGACY, TagMapType.LEGACY),
  OPTIMIZED_LEGACY(TagMapType.OPTIMIZED, TagMapType.LEGACY),
  LEGACY_OPTIMIZED(TagMapType.LEGACY, TagMapType.OPTIMIZED);

  public final TagMapType firstType;
  public final TagMapType secondType;

  TagMapTypePair(TagMapType firstType, TagMapType secondType) {
    this.firstType = firstType;
    this.secondType = secondType;
  }
}
