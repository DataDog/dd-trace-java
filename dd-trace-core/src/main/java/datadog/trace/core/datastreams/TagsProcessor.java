package datadog.trace.core.datastreams;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.function.Function;
import java.util.HashMap;
import java.util.Map;

public class TagsProcessor {
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

  public static final String TYPE_TAG = "type";
  private static final DDCache<String, String> TYPE_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> TYPE_TAG_PREFIX = new StringPrefix("type:");

  public static final String TOPIC_TAG = "topic";
  private static final DDCache<String, String> TOPIC_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> TOPIC_TAG_PREFIX = new StringPrefix("topic:");

  public static final String PARTITION_TAG = "partition";
  private static final DDCache<String, String> PARTITION_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> PARTITION_TAG_PREFIX =
      new StringPrefix("partition:");
  public static final String GROUP_TAG = "group";
  private static final DDCache<String, String> GROUP_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> GROUP_TAG_PREFIX = new StringPrefix("group:");

  public static final String EXCHANGE_TAG = "exchange";
  private static final DDCache<String, String> EXCHANGE_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> EXCHANGE_TAG_PREFIX = new StringPrefix("exchange:");

  public static final String HAS_ROUTING_KEY_TAG = "has_routing_key";
  private static final DDCache<String, String> HAS_ROUTING_KEY_TAG_CACHE =
      DDCaches.newFixedSizeCache(2); // true or false
  private static final Function<String, String> HAS_ROUTING_KEY_TAG_PREFIX =
      new StringPrefix("has_routing_key:");

  private static final Map<String, DDCache<String, String>> TAG_TO_CACHE = createTagToCacheMap();
  private static final Map<String, Function<String, String>> TAG_TO_PREFIX = createTagToPrefixMap();

  private static Map<String, DDCache<String, String>> createTagToCacheMap() {
    Map<String, DDCache<String, String>> result = new HashMap<String, DDCache<String, String>>();
    result.put(TYPE_TAG, TYPE_TAG_CACHE);
    result.put(TOPIC_TAG, TOPIC_TAG_CACHE);
    result.put(PARTITION_TAG, PARTITION_TAG_CACHE);
    result.put(GROUP_TAG, GROUP_TAG_CACHE);
    result.put(EXCHANGE_TAG, EXCHANGE_TAG_CACHE);
    result.put(HAS_ROUTING_KEY_TAG, HAS_ROUTING_KEY_TAG_CACHE);
    return result;
  }

  private static Map<String, Function<String, String>> createTagToPrefixMap() {
    Map<String, Function<String, String>> result = new HashMap<String, Function<String, String>>();
    result.put(TYPE_TAG, TYPE_TAG_PREFIX);
    result.put(TOPIC_TAG, TOPIC_TAG_PREFIX);
    result.put(PARTITION_TAG, PARTITION_TAG_PREFIX);
    result.put(GROUP_TAG, GROUP_TAG_PREFIX);
    result.put(EXCHANGE_TAG, EXCHANGE_TAG_PREFIX);
    result.put(HAS_ROUTING_KEY_TAG, HAS_ROUTING_KEY_TAG_PREFIX);
    return result;
  }

  public static final String createTag(String tagKey, String tagValue) {
    DDCache<String, String> cache = TAG_TO_CACHE.get(tagKey);
    Function<String, String> prefix = TAG_TO_PREFIX.get(tagKey);
    if (cache != null && prefix != null) {
      return cache.computeIfAbsent(tagValue, prefix);
    }
    return tagKey + ":" + tagValue;
  }
}
