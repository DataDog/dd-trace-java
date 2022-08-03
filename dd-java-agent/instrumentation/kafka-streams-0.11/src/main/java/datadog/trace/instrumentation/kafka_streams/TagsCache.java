package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.function.Function;

public class TagsCache {
  public static final class StringPrefix implements Function<String, String> {
    private final String prefix;

    public StringPrefix(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public String apply(String key) {
      return prefix + key;
    }
  }

  public static final DDCache<String, String> TOPIC_TAG_CACHE = DDCaches.newFixedSizeCache(32);

  // Use new function instead of Functions.Prefix to keep return type as String instead of
  // CharSequence.
  public static final Function<String, String> TOPIC_TAG_PREFIX = new StringPrefix("topic:");

  public static final DDCache<String, String> GROUP_TAG_CACHE = DDCaches.newFixedSizeCache(32);

  // Use new function instead of Functions.Prefix to keep return type as String instead of
  // CharSequence.
  public static final Function<String, String> GROUP_TAG_PREFIX = new StringPrefix("group:");
}
