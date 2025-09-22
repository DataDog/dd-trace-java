package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Iterator;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

public class IteratorAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void wrap(
      @Advice.Return(readOnly = false) Iterator<ConsumerRecord<?, ?>> iterator,
      @Advice.This ConsumerRecords records) {
    if (iterator != null) {
      KafkaConsumerInfo kafkaConsumerInfo =
          InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class).get(records);
      String group = KafkaConsumerInstrumentationHelper.extractGroup(kafkaConsumerInfo);
      String clusterId =
          KafkaConsumerInstrumentationHelper.extractClusterId(
              kafkaConsumerInfo, InstrumentationContext.get(Metadata.class, String.class));
      String bootstrapServers =
          KafkaConsumerInstrumentationHelper.extractBootstrapServers(kafkaConsumerInfo);
      iterator =
          new TracingIterator(
              iterator,
              KafkaDecorator.KAFKA_CONSUME,
              KafkaDecorator.CONSUMER_DECORATE,
              group,
              clusterId,
              bootstrapServers);
    }
  }
}
