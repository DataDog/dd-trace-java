package datadog.trace.instrumentation.kafka_common;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KafkaConfigHelperReportPendingConfigTest {
  private final AgentDataStreamsMonitoring dataStreams = mock(AgentDataStreamsMonitoring.class);
  private final boolean[] dataStreamsEnabled = {false};
  private AgentTracer.TracerAPI originalTracer;

  @BeforeEach
  void installTracer() {
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(invocation -> dataStreamsEnabled[0]);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    when(tracer.getDataStreamsMonitoring()).thenReturn(dataStreams);
    originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void restoreTracer() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void clientConfigIsKeptPendingWhileOffAndReportedOnceWhenOn() {
    MetadataState state = new MetadataState();
    AbstractConfig clientConfig =
        new AbstractConfig(
            new ConfigDef()
                .define("client.id", ConfigDef.Type.STRING, ConfigDef.Importance.LOW, "doc")
                .define("ssl.key.password", ConfigDef.Type.STRING, ConfigDef.Importance.LOW, "doc"),
            props("client.id", "producer-1", "ssl.key.password", "secret"));
    state.setPendingConfig(new PendingConfig("kafka_producer", "", clientConfig));

    KafkaConfigHelper.reportPendingConfig(state, "cluster-1");
    verify(dataStreams, never()).reportKafkaConfig(anyString(), anyString(), anyString(), any());

    dataStreamsEnabled[0] = true;
    KafkaConfigHelper.reportPendingConfig(state, "cluster-1");
    KafkaConfigHelper.reportPendingConfig(state, "cluster-1");

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("client.id", "producer-1");
    expected.put("ssl.key.password", KafkaConfigHelper.MASKED_VALUE);
    verify(dataStreams, times(1)).reportKafkaConfig("kafka_producer", "cluster-1", "", expected);
    assertNull(state.takePendingConfig());
  }

  @Test
  void rawMapConfigIsCopiedWhenStoredAndKeptPendingWhileOff() {
    MetadataState state = new MetadataState();
    Map<String, Object> rawConfig = props("group.id", "group-1", "ssl.key.password", "secret");
    KafkaConfigHelper.storePendingConsumerConfigIfAbsent(state, "group-1", rawConfig);
    // Copied when stored, so later map changes aren't reported
    rawConfig.put("group.id", "group-2");
    rawConfig.put("client.id", "consumer-2");

    KafkaConfigHelper.reportPendingConfig(state, "cluster-1");
    verify(dataStreams, never()).reportKafkaConfig(anyString(), anyString(), anyString(), any());

    dataStreamsEnabled[0] = true;
    KafkaConfigHelper.reportPendingConfig(state, "cluster-1");
    KafkaConfigHelper.reportPendingConfig(state, "cluster-1");

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("group.id", "group-1");
    expected.put("ssl.key.password", KafkaConfigHelper.MASKED_VALUE);
    verify(dataStreams, times(1))
        .reportKafkaConfig("kafka_consumer", "cluster-1", "group-1", expected);
  }

  @Test
  void rawMapConfigDoesNotReplaceAPendingConsumerConfig() {
    MetadataState state = new MetadataState();
    Map<String, Object> rawConfig =
        props(
            "bootstrap.servers",
            "localhost:9092",
            "group.id",
            "group-1",
            "key.deserializer",
            StringDeserializer.class.getName(),
            "value.deserializer",
            StringDeserializer.class.getName());
    ConsumerConfig consumerConfig = new ConsumerConfig(rawConfig);
    KafkaConfigHelper.storePendingConsumerConfig(state, "group-1", consumerConfig);
    KafkaConfigHelper.storePendingConsumerConfigIfAbsent(state, "group-1", rawConfig);

    dataStreamsEnabled[0] = true;
    KafkaConfigHelper.reportPendingConfig(state, "cluster-1");

    verify(dataStreams, times(1))
        .reportKafkaConfig(
            "kafka_consumer",
            "cluster-1",
            "group-1",
            KafkaConfigHelper.extractClientConfig(consumerConfig));
  }

  private static Map<String, Object> props(String... keyValues) {
    Map<String, Object> props = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      props.put(keyValues[i], keyValues[i + 1]);
    }
    return props;
  }
}
