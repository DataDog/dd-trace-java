package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.producer.ProducerConfig;

public class ProducerConstructorAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void captureConfiguration(
      @Advice.FieldValue("metadata") Metadata metadata,
      @Advice.Argument(0) ProducerConfig producerConfig) {
    if (Config.get().isDataStreamsEnabled()) {
      MetadataState state =
          InstrumentationContext.get(Metadata.class, MetadataState.class)
              .putIfAbsent(metadata, new MetadataState());
      KafkaConfigHelper.storePendingProducerConfig(
          state, KafkaConfigHelper.extractProducerConfig(producerConfig));
    }
  }
}
