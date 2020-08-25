package datadog.trace.bootstrap.instrumentation.cache;

public final class DDCaches {

  private static final boolean DEFAULT_TO_FIXED_SIZE_CACHE = true;

  public static <K, V> DDCache<K, V> newCache(final int capacity) {
    return DEFAULT_TO_FIXED_SIZE_CACHE
        ? new FixedSizeCache<K, V>(capacity)
        : new CHMCache<K, V>(capacity);
  }

  public static <K, V> DDCache<K, V> newFixedSizeCache(final int capacity) {
    return new FixedSizeCache<>(capacity);
  }

  public static <K, V> DDCache<K, V> newUnboundedCache(final int capacity) {
    return new CHMCache<>(capacity);
  }
}
