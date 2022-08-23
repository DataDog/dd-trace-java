package datadog.trace.instrumentation.rabbitmq.amqp;

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

  public static final DDCache<String, String> EXCHANGE_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  public static final Function<String, String> EXCHANGE_TAG_PREFIX = new StringPrefix("exchange:");
}
