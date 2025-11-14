package datadog.trace.instrumentation.confluentschemaregistry;

/**
 * Thread-local context for passing topic, schema, and cluster information between serialization and
 * registry calls.
 */
public class SchemaRegistryContext {
  private static final ThreadLocal<String> currentTopic = new ThreadLocal<>();
  private static final ThreadLocal<String> clusterId = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> isKey = new ThreadLocal<>();
  private static final ThreadLocal<Integer> keySchemaId = new ThreadLocal<>();
  private static final ThreadLocal<Integer> valueSchemaId = new ThreadLocal<>();

  public static void setTopic(String topic) {
    currentTopic.set(topic);
  }

  public static String getTopic() {
    return currentTopic.get();
  }

  public static void setClusterId(String cluster) {
    clusterId.set(cluster);
  }

  public static String getClusterId() {
    return clusterId.get();
  }

  public static void setIsKey(boolean key) {
    isKey.set(key);
  }

  public static Boolean getIsKey() {
    return isKey.get();
  }

  public static void setKeySchemaId(Integer schemaId) {
    keySchemaId.set(schemaId);
  }

  public static Integer getKeySchemaId() {
    return keySchemaId.get();
  }

  public static void setValueSchemaId(Integer schemaId) {
    valueSchemaId.set(schemaId);
  }

  public static Integer getValueSchemaId() {
    return valueSchemaId.get();
  }

  public static void clear() {
    currentTopic.remove();
    clusterId.remove();
    isKey.remove();
    keySchemaId.remove();
    valueSchemaId.remove();
  }
}
