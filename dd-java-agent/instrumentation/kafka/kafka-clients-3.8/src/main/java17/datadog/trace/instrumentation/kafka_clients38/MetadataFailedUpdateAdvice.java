package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class MetadataFailedUpdateAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(@Advice.This final Metadata metadata) {
    MetadataState state =
        InstrumentationContext.get(Metadata.class, MetadataState.class).get(metadata);
    if (state != null) {
      KafkaConfigHelper.reportPendingConfigAsFailed(state);
    }
  }

  public static void muzzleCheck(ConsumerRecord record) {
    record.headers();
  }
}
