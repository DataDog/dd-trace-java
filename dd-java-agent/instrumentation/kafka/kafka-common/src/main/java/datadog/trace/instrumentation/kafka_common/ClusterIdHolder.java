package datadog.trace.instrumentation.kafka_common;

/**
 * Thread-local holder for Kafka cluster ID to be used during schema registry operations. The Kafka
 * producer/consumer instrumentation sets this before serialization/deserialization, and the schema
 * registry serializer/deserializer instrumentation reads it.
 */
public class ClusterIdHolder {
  private static final ThreadLocal<String> CLUSTER_ID = new ThreadLocal<>();

  public static void set(String clusterId) {
    CLUSTER_ID.set(clusterId);
  }

  public static String get() {
    return CLUSTER_ID.get();
  }

  public static void clear() {
    CLUSTER_ID.set(null);
  }
}
