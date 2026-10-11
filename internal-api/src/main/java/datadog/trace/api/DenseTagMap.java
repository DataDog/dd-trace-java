package datadog.trace.api;

/**
 * The dense/hash hybrid {@link TagMap}: known tags are stored densely, by id, with no per-tag
 * {@link TagMap.Entry}; custom tags stay in the buckets. Used when the dense store is on; see
 * {@link TagMapFactory}.
 */
final class DenseTagMap extends TagMap {
  DenseTagMap(TagMap parent) {
    super(parent);
  }

  DenseTagMap(Object[] buckets, int size) {
    super(buckets, size);
  }
}
