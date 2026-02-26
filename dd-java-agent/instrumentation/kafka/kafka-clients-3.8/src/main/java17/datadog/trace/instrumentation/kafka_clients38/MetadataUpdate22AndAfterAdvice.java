package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.requests.MetadataResponse;

public class MetadataUpdate22AndAfterAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Metadata metadata, @Advice.Argument(1) final MetadataResponse response) {
    if (response != null) {
      String clusterId = response.clusterId();
      MetadataState state =
          InstrumentationContext.get(Metadata.class, MetadataState.class).get(metadata);
      if (state == null) {
        state = new MetadataState();
        InstrumentationContext.get(Metadata.class, MetadataState.class).put(metadata, state);
      }
      state.clusterId = clusterId;
      KafkaConfigHelper.reportPendingConfig(state, clusterId);
    }
  }

  public static void muzzleCheck(ConsumerRecord record) {
    // KafkaConsumerInstrumentation only applies for kafka versions with headers
    // Make an explicit call so MetadataInstrumentation does the same
    record.headers();
  }
}
