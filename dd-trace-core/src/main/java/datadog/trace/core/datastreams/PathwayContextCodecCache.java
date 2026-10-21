package datadog.trace.core.datastreams;

/**
 * Bounded lock-free direct-mapped caches for the {@code dd-pathway-ctx-base64} header codec. The
 * encoded and decoded tables are independent. Entries are immutable, so racy stores only cost a
 * later miss.
 */
final class PathwayContextCodecCache {
  private static final int SIZE = 64;
  private static final Encoded[] ENCODED = new Encoded[SIZE];
  private static final Decoded[] DECODED = new Decoded[SIZE];

  private PathwayContextCodecCache() {}

  /** Returns the cached header value for the given triple, or {@code null} on a miss. */
  static String getEncoded(long hash, long pathwayStartMillis, long edgeStartMillis) {
    Encoded entry = ENCODED[encodedIndex(hash, pathwayStartMillis, edgeStartMillis)];
    if (entry != null
        && entry.hash == hash
        && entry.pathwayStartMillis == pathwayStartMillis
        && entry.edgeStartMillis == edgeStartMillis) {
      return entry.value;
    }
    return null;
  }

  static void putEncoded(long hash, long pathwayStartMillis, long edgeStartMillis, String value) {
    ENCODED[encodedIndex(hash, pathwayStartMillis, edgeStartMillis)] =
        new Encoded(hash, pathwayStartMillis, edgeStartMillis, value);
  }

  /** Returns the cached decoded triple for the given header value, or {@code null} on a miss. */
  static Decoded getDecoded(String value) {
    Decoded entry = DECODED[decodedIndex(value)];
    if (entry != null && value.equals(entry.value)) {
      return entry;
    }
    return null;
  }

  static void putDecoded(String value, long hash, long pathwayStartMillis, long edgeStartMillis) {
    DECODED[decodedIndex(value)] = new Decoded(value, hash, pathwayStartMillis, edgeStartMillis);
  }

  private static int encodedIndex(long hash, long pathwayStartMillis, long edgeStartMillis) {
    long h = hash * 31 + pathwayStartMillis;
    h = h * 31 + edgeStartMillis;
    int i = (int) (h ^ (h >>> 32));
    i ^= i >>> 16;
    return i & (SIZE - 1);
  }

  private static int decodedIndex(String value) {
    int i = value.hashCode();
    i ^= i >>> 16;
    return i & (SIZE - 1);
  }

  static final class Encoded {
    final long hash;
    final long pathwayStartMillis;
    final long edgeStartMillis;
    final String value;

    Encoded(long hash, long pathwayStartMillis, long edgeStartMillis, String value) {
      this.hash = hash;
      this.pathwayStartMillis = pathwayStartMillis;
      this.edgeStartMillis = edgeStartMillis;
      this.value = value;
    }
  }

  static final class Decoded {
    final String value;
    final long hash;
    final long pathwayStartMillis;
    final long edgeStartMillis;

    Decoded(String value, long hash, long pathwayStartMillis, long edgeStartMillis) {
      this.value = value;
      this.hash = hash;
      this.pathwayStartMillis = pathwayStartMillis;
      this.edgeStartMillis = edgeStartMillis;
    }
  }
}
