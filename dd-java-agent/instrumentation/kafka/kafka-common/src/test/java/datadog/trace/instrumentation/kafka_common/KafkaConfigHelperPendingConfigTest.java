package datadog.trace.instrumentation.kafka_common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import org.junit.jupiter.api.Test;

class KafkaConfigHelperPendingConfigTest {
  @Test
  void keepsTheConfigUntilTheClusterIdIsKnown() {
    MetadataState state = new MetadataState();

    KafkaConfigHelper.storePendingProducerConfig(state, Collections.singletonMap("acks", "all"));

    assertEquals("kafka_producer", state.takePendingConfig().type);
  }

  // The metadata update recorded the cluster ID before the constructor advice stored the config.
  @Test
  void reportsTheConfigRightAwayWhenTheClusterIdIsAlreadyKnown() {
    MetadataState state = new MetadataState();
    state.clusterId = "cluster-1";

    KafkaConfigHelper.storePendingProducerConfig(state, Collections.singletonMap("acks", "all"));
    KafkaConfigHelper.storePendingConsumerConfig(
        state, "group", Collections.singletonMap("group.id", "group"));

    assertNull(state.takePendingConfig());
  }
}
