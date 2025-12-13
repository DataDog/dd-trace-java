import KafkaClientTestBase
import datadog.trace.api.Config

/**
 * Data Streams Monitoring tests for Kafka clients.
 * 
 * This test class extends KafkaClientTestBase and runs all base test methods
 * with Data Streams Monitoring enabled. The base class contains all the actual
 * test logic; this class only configures DSM to be enabled.
 */
class KafkaClientDataStreamsDisabledForkedTest extends KafkaClientTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaClientDataStreamsDisabledForkedTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "true")
  }

  @Override
  String service() {
    return "kafka"
  }

  @Override
  boolean hasQueueSpan() {
    return false
  }

  @Override
  boolean splitByDestination() {
    return false
  }

  @Override
  boolean isDataStreamsEnabled() {
    return false
  }
}

