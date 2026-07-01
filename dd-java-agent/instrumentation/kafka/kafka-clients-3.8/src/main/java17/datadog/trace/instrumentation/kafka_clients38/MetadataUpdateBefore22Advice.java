package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Cluster;

public class MetadataUpdateBefore22Advice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Metadata metadata, @Advice.Argument(0) final Cluster newCluster) {
    if (newCluster != null && !newCluster.isBootstrapConfigured()) {
      String clusterId = newCluster.clusterResource().clusterId();
      MetadataState state =
          InstrumentationContext.get(Metadata.class, MetadataState.class)
              .putIfAbsent(metadata, MetadataState::new);
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
