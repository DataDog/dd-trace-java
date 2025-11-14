package datadog.trace.instrumentation.confluentschemaregistry;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects and logs metrics about Schema Registry operations including schema registrations,
 * compatibility checks, and serialization/deserialization operations. Also reports to Data Streams
 * Monitoring.
 */
public class SchemaRegistryMetrics {
  private static final Logger log = LoggerFactory.getLogger(SchemaRegistryMetrics.class);

  // Counters for different operations
  private static final AtomicLong schemaRegistrationSuccess = new AtomicLong(0);
  private static final AtomicLong schemaRegistrationFailure = new AtomicLong(0);
  private static final AtomicLong schemaCompatibilitySuccess = new AtomicLong(0);
  private static final AtomicLong schemaCompatibilityFailure = new AtomicLong(0);
  private static final AtomicLong serializationSuccess = new AtomicLong(0);
  private static final AtomicLong serializationFailure = new AtomicLong(0);
  private static final AtomicLong deserializationSuccess = new AtomicLong(0);
  private static final AtomicLong deserializationFailure = new AtomicLong(0);

  // Track schema IDs per topic for context
  private static final ConcurrentHashMap<String, Integer> topicKeySchemaIds =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Integer> topicValueSchemaIds =
      new ConcurrentHashMap<>();

  public static void recordSchemaRegistration(
      String subject, int schemaId, boolean isKey, String topic) {
    schemaRegistrationSuccess.incrementAndGet();
    if (topic != null) {
      if (isKey) {
        topicKeySchemaIds.put(topic, schemaId);
      } else {
        topicValueSchemaIds.put(topic, schemaId);
      }
    }
    log.info(
        "[Schema Registry] Schema registered successfully - Subject: {}, Schema ID: {}, Is Key: {}, Topic: {}",
        subject,
        schemaId,
        isKey,
        topic);
  }

  public static void recordSchemaRegistrationFailure(
      String subject, String errorMessage, boolean isKey, String topic) {
    schemaRegistrationFailure.incrementAndGet();
    log.error(
        "[Schema Registry] Schema registration FAILED - Subject: {}, Is Key: {}, Topic: {}, Error: {}",
        subject,
        isKey,
        topic,
        errorMessage);
  }

  public static void recordCompatibilityCheck(
      String subject, boolean compatible, String errorMessage) {
    if (compatible) {
      schemaCompatibilitySuccess.incrementAndGet();
      log.info("[Schema Registry] Schema compatibility check PASSED - Subject: {}", subject);
    } else {
      schemaCompatibilityFailure.incrementAndGet();
      log.error(
          "[Schema Registry] Schema compatibility check FAILED - Subject: {}, Error: {}",
          subject,
          errorMessage);
    }
  }

  public static void recordSerialization(
      String topic, Integer keySchemaId, Integer valueSchemaId, boolean isKey) {
    serializationSuccess.incrementAndGet();

    String clusterId = SchemaRegistryContext.getClusterId();
    log.info("[Schema Registry] DEBUG: Retrieved clusterId from context: '{}'", clusterId);
    log.info(
        "[Schema Registry] Produce to topic '{}', cluster: {}, schema for key: {}, schema for value: {}, serializing: {}",
        topic,
        clusterId != null ? clusterId : "unknown",
        keySchemaId != null ? keySchemaId : "none",
        valueSchemaId != null ? valueSchemaId : "none",
        isKey ? "KEY" : "VALUE");

    // Report to Data Streams Monitoring
    Integer schemaId = isKey ? keySchemaId : valueSchemaId;
    if (schemaId != null && topic != null) {
      try {
        AgentTracer.get()
            .getDataStreamsMonitoring()
            .setSchemaRegistryUsage(topic, clusterId, schemaId, true, isKey);
      } catch (Throwable t) {
        // Don't fail the application if DSM reporting fails
        log.debug("Failed to report schema registry usage to DSM", t);
      }
    }
  }

  public static void recordSerializationFailure(String topic, String errorMessage, boolean isKey) {
    serializationFailure.incrementAndGet();

    String clusterId = SchemaRegistryContext.getClusterId();
    log.error(
        "[Schema Registry] Serialization FAILED for topic '{}', cluster: {}, {} - Error: {}",
        topic,
        clusterId != null ? clusterId : "unknown",
        isKey ? "KEY" : "VALUE",
        errorMessage);

    // Report to Data Streams Monitoring (use -1 as schema ID for failures)
    if (topic != null) {
      try {
        AgentTracer.get()
            .getDataStreamsMonitoring()
            .setSchemaRegistryUsage(topic, clusterId, -1, false, isKey);
      } catch (Throwable t) {
        // Don't fail the application if DSM reporting fails
        log.debug("Failed to report schema registry failure to DSM", t);
      }
    }
  }

  public static void recordDeserialization(
      String topic, Integer keySchemaId, Integer valueSchemaId, boolean isKey) {
    deserializationSuccess.incrementAndGet();

    String clusterId = SchemaRegistryContext.getClusterId();
    log.info(
        "[Schema Registry] Consume from topic '{}', cluster: {}, schema for key: {}, schema for value: {}, deserializing: {}",
        topic,
        clusterId != null ? clusterId : "unknown",
        keySchemaId != null ? keySchemaId : "none",
        valueSchemaId != null ? valueSchemaId : "none",
        isKey ? "KEY" : "VALUE");

    // Report to Data Streams Monitoring
    Integer schemaId = isKey ? keySchemaId : valueSchemaId;
    if (schemaId != null && topic != null) {
      try {
        AgentTracer.get()
            .getDataStreamsMonitoring()
            .setSchemaRegistryUsage(topic, clusterId, schemaId, true, isKey);
      } catch (Throwable t) {
        // Don't fail the application if DSM reporting fails
        log.debug("Failed to report schema registry usage to DSM", t);
      }
    }
  }

  public static void recordDeserializationFailure(
      String topic, String errorMessage, boolean isKey) {
    deserializationFailure.incrementAndGet();

    String clusterId = SchemaRegistryContext.getClusterId();
    log.error(
        "[Schema Registry] Deserialization FAILED for topic '{}', cluster: {}, {} - Error: {}",
        topic,
        clusterId != null ? clusterId : "unknown",
        isKey ? "KEY" : "VALUE",
        errorMessage);

    // Report to Data Streams Monitoring (use -1 as schema ID for failures)
    if (topic != null) {
      try {
        AgentTracer.get()
            .getDataStreamsMonitoring()
            .setSchemaRegistryUsage(topic, clusterId, -1, false, isKey);
      } catch (Throwable t) {
        // Don't fail the application if DSM reporting fails
        log.debug("Failed to report schema registry failure to DSM", t);
      }
    }
  }

  public static void recordSchemaRetrieval(int schemaId, String schemaType) {
    log.debug(
        "[Schema Registry] Retrieved schema from registry - Schema ID: {}, Type: {}",
        schemaId,
        schemaType);
  }

  // Methods to get current counts for metrics reporting
  public static long getSchemaRegistrationSuccessCount() {
    return schemaRegistrationSuccess.get();
  }

  public static long getSchemaRegistrationFailureCount() {
    return schemaRegistrationFailure.get();
  }

  public static long getSchemaCompatibilitySuccessCount() {
    return schemaCompatibilitySuccess.get();
  }

  public static long getSchemaCompatibilityFailureCount() {
    return schemaCompatibilityFailure.get();
  }

  public static long getSerializationSuccessCount() {
    return serializationSuccess.get();
  }

  public static long getSerializationFailureCount() {
    return serializationFailure.get();
  }

  public static long getDeserializationSuccessCount() {
    return deserializationSuccess.get();
  }

  public static long getDeserializationFailureCount() {
    return deserializationFailure.get();
  }

  public static void printSummary() {
    log.info("========== Schema Registry Metrics Summary ==========");
    log.info(
        "Schema Registrations - Success: {}, Failure: {}",
        schemaRegistrationSuccess.get(),
        schemaRegistrationFailure.get());
    log.info(
        "Compatibility Checks - Success: {}, Failure: {}",
        schemaCompatibilitySuccess.get(),
        schemaCompatibilityFailure.get());
    log.info(
        "Serializations - Success: {}, Failure: {}",
        serializationSuccess.get(),
        serializationFailure.get());
    log.info(
        "Deserializations - Success: {}, Failure: {}",
        deserializationSuccess.get(),
        deserializationFailure.get());
    log.info("=====================================================");
  }
}
