package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.KAFKA_CONSUME;

import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

public class ListAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void wrap(
      @Advice.Return(readOnly = false) List<ConsumerRecord<?, ?>> iterable,
      @Advice.This ConsumerRecords records) {
    if (iterable != null) {
      KafkaConsumerInfo kafkaConsumerInfo =
          InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class).get(records);
      String group = KafkaConsumerInstrumentationHelper.extractGroup(kafkaConsumerInfo);
      String clusterId =
          KafkaConsumerInstrumentationHelper.extractClusterId(
              kafkaConsumerInfo, InstrumentationContext.get(Metadata.class, String.class));
      String bootstrapServers =
          KafkaConsumerInstrumentationHelper.extractBootstrapServers(kafkaConsumerInfo);
      iterable =
          new TracingList(
              iterable, KAFKA_CONSUME, CONSUMER_DECORATE, group, clusterId, bootstrapServers);
    }
  }
}
