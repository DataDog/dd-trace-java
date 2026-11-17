package datadog.trace.instrumentation.kafka_clients38;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import org.apache.kafka.clients.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KafkaConsumerInstrumentationHelperTest {

  @SuppressWarnings("unchecked")
  private final ContextStore<Metadata, MetadataState> metadataContextStore =
      mock(ContextStore.class);

  @Test
  void extractGroupReturnsNullForNullKafkaConsumerInfo() {
    assertNull(KafkaConsumerInstrumentationHelper.extractGroup(null));
  }

  @Test
  void extractGroupReturnsNullWhenConsumerGroupIsNull() {
    KafkaConsumerInfo kafkaConsumerInfo = new KafkaConsumerInfo(null, null, "localhost:9092");
    assertNull(KafkaConsumerInstrumentationHelper.extractGroup(kafkaConsumerInfo));
  }

  @Test
  void extractGroupReturnsConsumerGroupWhenPresent() {
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo("test-group", null, "localhost:9092");
    assertEquals("test-group", KafkaConsumerInstrumentationHelper.extractGroup(kafkaConsumerInfo));
  }

  @Test
  void extractBootstrapServersReturnsNullForNullKafkaConsumerInfo() {
    assertNull(KafkaConsumerInstrumentationHelper.extractBootstrapServers(null));
  }

  @Test
  void extractBootstrapServersReturnsNullWhenBootstrapServersIsNull() {
    KafkaConsumerInfo kafkaConsumerInfo = new KafkaConsumerInfo("test-group", null, null);
    assertNull(KafkaConsumerInstrumentationHelper.extractBootstrapServers(kafkaConsumerInfo));
  }

  @Test
  void extractBootstrapServersReturnsValueWhenPresent() {
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo("test-group", null, "localhost:9092");
    assertEquals(
        "localhost:9092",
        KafkaConsumerInstrumentationHelper.extractBootstrapServers(kafkaConsumerInfo));
  }

  @Test
  void extractClusterIdReturnsNullForNullKafkaConsumerInfo() {
    assertNull(KafkaConsumerInstrumentationHelper.extractClusterId(null, metadataContextStore));
  }

  @Test
  void extractClusterIdReturnsNullWhenMetadataIsNull() {
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo("test-group", null, "localhost:9092");
    assertNull(
        KafkaConsumerInstrumentationHelper.extractClusterId(
            kafkaConsumerInfo, metadataContextStore));
  }

  @Test
  void extractClusterIdReturnsNullWhenNoStateForMetadata() {
    Metadata metadata = mock(Metadata.class);
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo("test-group", metadata, "localhost:9092");
    when(metadataContextStore.get(metadata)).thenReturn(null);
    assertNull(
        KafkaConsumerInstrumentationHelper.extractClusterId(
            kafkaConsumerInfo, metadataContextStore));
  }

  @Test
  void extractClusterIdReturnsClusterIdWhenStatePresent() {
    Metadata metadata = mock(Metadata.class);
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo("test-group", metadata, "localhost:9092");
    MetadataState state = new MetadataState();
    state.clusterId = "cluster-1";
    when(metadataContextStore.get(metadata)).thenReturn(state);
    assertEquals(
        "cluster-1",
        KafkaConsumerInstrumentationHelper.extractClusterId(
            kafkaConsumerInfo, metadataContextStore));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void extractClusterIdForDataStreamsOnlyFollowsLiveConfig(boolean dataStreamsEnabled) {
    Metadata metadata = mock(Metadata.class);
    KafkaConsumerInfo kafkaConsumerInfo =
        new KafkaConsumerInfo("test-group", metadata, "localhost:9092", true);
    MetadataState state = new MetadataState();
    state.clusterId = "cluster-1";
    when(metadataContextStore.get(metadata)).thenReturn(state);

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(dataStreamsEnabled);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    AgentTracer.TracerAPI originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracer);
    try {
      assertEquals(
          dataStreamsEnabled ? "cluster-1" : null,
          KafkaConsumerInstrumentationHelper.extractClusterId(
              kafkaConsumerInfo, metadataContextStore));
    } finally {
      AgentTracer.forceRegister(originalTracer);
    }
  }
}
