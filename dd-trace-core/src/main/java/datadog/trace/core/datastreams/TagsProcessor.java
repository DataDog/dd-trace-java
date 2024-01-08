package datadog.trace.core.datastreams;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

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

  public static final String DIRECTION_TAG = "direction";
  // service centric direction - data flowing into the service
  public static final String DIRECTION_IN = "in";
  // service centric direction - data flowing out of the service
  public static final String DIRECTION_OUT = "out";
  private static final DDCache<String, String> DIRECTION_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> DIRECTION_TAG_PREFIX =
      new StringPrefix("direction:");
  public static final String TOPIC_TAG = "topic";
  private static final DDCache<String, String> TOPIC_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> TOPIC_TAG_PREFIX = new StringPrefix("topic:");

  public static final String PARTITION_TAG = "partition";
  private static final DDCache<String, String> PARTITION_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> PARTITION_TAG_PREFIX =
      new StringPrefix("partition:");
  public static final String GROUP_TAG = "group";
  public static final String CONSUMER_GROUP_TAG = "consumer_group";
  private static final DDCache<String, String> GROUP_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final DDCache<String, String> CONSUMER_GROUP_TAG_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> GROUP_TAG_PREFIX = new StringPrefix("group:");
  private static final Function<String, String> CONSUMER_GROUP_TAG_PREFIX =
      new StringPrefix("consumer_group:");
  public static final String SUBSCRIPTION_TAG = "subscription";
  private static final DDCache<String, String> SUBSCRIPTION_TAG_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> SUBSCRIPTION_TAG_PREFIX =
      new StringPrefix("subscription:");
  public static final String EXCHANGE_TAG = "exchange";
  private static final DDCache<String, String> EXCHANGE_TAG_CACHE = DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> EXCHANGE_TAG_PREFIX = new StringPrefix("exchange:");

  public static final String HAS_ROUTING_KEY_TAG = "has_routing_key";
  private static final DDCache<String, String> HAS_ROUTING_KEY_TAG_CACHE =
      DDCaches.newFixedSizeCache(2); // true or false
  private static final Function<String, String> HAS_ROUTING_KEY_TAG_PREFIX =
      new StringPrefix("has_routing_key:");

  public static final String KAFKA_CLUSTER_ID_TAG = "kafka_cluster_id";
  private static final DDCache<String, String> KAFKA_CLUSTER_ID_TAG_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Function<String, String> KAFKA_CLUSTER_ID_TAG_PREFIX =
      new StringPrefix("kafka_cluster_id:");

  private static final Map<String, DDCache<String, String>> TAG_TO_CACHE = createTagToCacheMap();
  private static final Map<String, Function<String, String>> TAG_TO_PREFIX = createTagToPrefixMap();

  private static Map<String, DDCache<String, String>> createTagToCacheMap() {
    Map<String, DDCache<String, String>> result = new HashMap<>();
    result.put(TYPE_TAG, TYPE_TAG_CACHE);
    result.put(DIRECTION_TAG, DIRECTION_TAG_CACHE);
    result.put(TOPIC_TAG, TOPIC_TAG_CACHE);
    result.put(PARTITION_TAG, PARTITION_TAG_CACHE);
    result.put(GROUP_TAG, GROUP_TAG_CACHE);
    result.put(CONSUMER_GROUP_TAG, CONSUMER_GROUP_TAG_CACHE);
    result.put(SUBSCRIPTION_TAG, SUBSCRIPTION_TAG_CACHE);
    result.put(EXCHANGE_TAG, EXCHANGE_TAG_CACHE);
    result.put(HAS_ROUTING_KEY_TAG, HAS_ROUTING_KEY_TAG_CACHE);
    result.put(KAFKA_CLUSTER_ID_TAG, KAFKA_CLUSTER_ID_TAG_CACHE);
    return result;
  }

  private static Map<String, Function<String, String>> createTagToPrefixMap() {
    Map<String, Function<String, String>> result = new HashMap<>();
    result.put(TYPE_TAG, TYPE_TAG_PREFIX);
    result.put(DIRECTION_TAG, DIRECTION_TAG_PREFIX);
    result.put(TOPIC_TAG, TOPIC_TAG_PREFIX);
    result.put(PARTITION_TAG, PARTITION_TAG_PREFIX);
    result.put(GROUP_TAG, GROUP_TAG_PREFIX);
    result.put(CONSUMER_GROUP_TAG, CONSUMER_GROUP_TAG_PREFIX);
    result.put(SUBSCRIPTION_TAG, SUBSCRIPTION_TAG_PREFIX);
    result.put(EXCHANGE_TAG, EXCHANGE_TAG_PREFIX);
    result.put(HAS_ROUTING_KEY_TAG, HAS_ROUTING_KEY_TAG_PREFIX);
    result.put(KAFKA_CLUSTER_ID_TAG, KAFKA_CLUSTER_ID_TAG_PREFIX);
    return result;
  }

  // Creates the tag string using the provided tagKey and tagValue.
  // Returns null if either tagKey or tagValue is null.
  public static String createTag(String tagKey, String tagValue) {
    if (tagKey == null || tagValue == null) {
      return null;
    }
    DDCache<String, String> cache = TAG_TO_CACHE.get(tagKey);
    Function<String, String> prefix = TAG_TO_PREFIX.get(tagKey);
    if (cache != null && prefix != null) {
      return cache.computeIfAbsent(tagValue, prefix);
    }
    return tagKey + ":" + tagValue;
  }
}
