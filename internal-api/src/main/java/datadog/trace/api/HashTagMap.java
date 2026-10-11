package datadog.trace.api;

/**
 * The hash-only {@link TagMap}: every tag, known or custom, is an {@link TagMap.Entry} in the
 * buckets. Used when the dense store is off; see {@link TagMapFactory}.
 */
final class HashTagMap extends TagMap {
  HashTagMap(TagMap parent) {
    super(parent);
  }

  HashTagMap(Object[] buckets, int size) {
    super(buckets, size);
  }
}
