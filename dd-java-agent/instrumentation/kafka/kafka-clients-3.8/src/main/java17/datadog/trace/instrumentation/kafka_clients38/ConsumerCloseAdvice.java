package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.internals.ConsumerDelegate;

/**
 * Finishes a {@code kafka.consume} span left active past the last poll when {@code close()}/{@code
 * unsubscribe()} ends the poll loop (see {@link TracingIterator}).
 */
public class ConsumerCloseAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(@Advice.This ConsumerDelegate consumer) {
    if (Config.get().isKafkaCreateConsumerScopeEnabled()) {
      KafkaConsumerInfo kafkaConsumerInfo =
          InstrumentationContext.get(ConsumerDelegate.class, KafkaConsumerInfo.class).get(consumer);
      KafkaConsumerInstrumentationHelper.closeLingeringConsumeScope(kafkaConsumerInfo);
    }
  }
}
