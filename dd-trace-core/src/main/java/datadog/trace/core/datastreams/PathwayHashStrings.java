package datadog.trace.core.datastreams;

/** Bounded lock-free cache of unsigned decimal strings for the few distinct pathway hashes. */
final class PathwayHashStrings {
  private static final int SIZE = 128;
  private static final Entry[] TABLE = new Entry[SIZE];

  private PathwayHashStrings() {}

  /** Returns the same value as {@link Long#toUnsignedString(long)}. */
  static String toUnsignedString(long hash) {
    int index = (int) (hash ^ (hash >>> 32)) & (SIZE - 1);
    Entry entry = TABLE[index];
    if (entry != null && entry.hash == hash) {
      return entry.value;
    }
    String value = Long.toUnsignedString(hash);
    TABLE[index] = new Entry(hash, value);
    return value;
  }

  private static final class Entry {
    final long hash;
    final String value;

    Entry(long hash, String value) {
      this.hash = hash;
      this.value = value;
    }
  }
}
