package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_RECORDS_COUNT;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.KAFKA_POLL;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.kafka_common.ClusterIdHolder;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.internals.ConsumerDelegate;

/**
 * this method transfers the consumer group from the KafkaConsumer class key to the ConsumerRecords
 * key. This is necessary because in the poll method, we don't have access to the KafkaConsumer
 * class.
 */
public class RecordsAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.This ConsumerDelegate consumer) {
    // Set cluster ID in ClusterIdHolder for Schema Registry instrumentation
    KafkaConsumerInfo kafkaConsumerInfo =
        InstrumentationContext.get(ConsumerDelegate.class, KafkaConsumerInfo.class).get(consumer);
    if (kafkaConsumerInfo != null && Config.get().isDataStreamsEnabled()) {
      String clusterId =
          KafkaConsumerInstrumentationHelper.extractClusterId(
              kafkaConsumerInfo, InstrumentationContext.get(Metadata.class, String.class));
      if (clusterId != null) {
        ClusterIdHolder.set(clusterId);
      }
    }

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
    // Clear cluster ID from Schema Registry instrumentation
    ClusterIdHolder.clear();

    if (scope == null) {
      return;
    }
    AgentSpan span = scope.span();
    span.setTag(KAFKA_RECORDS_COUNT, recordsCount);
    span.finish();
    scope.close();
  }
}
