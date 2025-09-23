package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_RECORDS_COUNT;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.KAFKA_POLL;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.internals.ConsumerDelegate;

/**
 * this method transfers the consumer group from the KafkaConsumer class key to the ConsumerRecords
 * key. This is necessary because in the poll method, we don't have access to the KafkaConsumer
 * class.
 */
public class RecordsAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter() {
    if (traceConfig().isDataStreamsEnabled()) {
      final AgentSpan span = startSpan(KAFKA_POLL);
      return activateSpan(span);
    }
    return null;
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void captureGroup(
      @Advice.Enter final AgentScope scope,
      @Advice.This ConsumerDelegate consumer,
      @Advice.Return ConsumerRecords records) {
    int recordsCount = 0;
    if (records != null) {
      // new - we are getting the KafkaConsumerInfo from the ConsumerDelegate instead of
      // KafkaConsumer
      KafkaConsumerInfo kafkaConsumerInfo =
          InstrumentationContext.get(ConsumerDelegate.class, KafkaConsumerInfo.class).get(consumer);
      if (kafkaConsumerInfo != null) {
        InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class)
            .put(records, kafkaConsumerInfo);
      }
      recordsCount = records.count();
    }
    if (scope == null) {
      return;
    }
    AgentSpan span = scope.span();
    span.setTag(KAFKA_RECORDS_COUNT, recordsCount);
    span.finish();
    scope.close();
  }
}
