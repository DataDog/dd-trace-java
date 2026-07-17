package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;

public class JoinGroupAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void trackJoinGroup(
      @Advice.This ConsumerCoordinator coordinator,
      @Advice.Argument(0) final int generationId,
      @Advice.Argument(1) final String memberId,
      @Advice.Argument(2) final String memberProtocol) {
    if (memberId == null || memberId.isEmpty()) {
      return;
    }
    KafkaConsumerInfo kafkaConsumerInfo =
        InstrumentationContext.get(ConsumerCoordinator.class, KafkaConsumerInfo.class)
            .get(coordinator);
    if (kafkaConsumerInfo == null) {
      return;
    }
    // Only report when the membership changes (new member id or new generation) to avoid
    // re-reporting an unchanged membership.
    if (memberId.equals(kafkaConsumerInfo.getLastReportedMemberId().orElse(null))
        && generationId == kafkaConsumerInfo.getLastReportedGenerationId()) {
      return;
    }
    kafkaConsumerInfo.setLastReportedMembership(memberId, generationId);

    String consumerGroup = kafkaConsumerInfo.getConsumerGroup().orElse(null);
    Metadata consumerMetadata = kafkaConsumerInfo.getmetadata().orElse(null);
    String clusterId = null;
    if (consumerMetadata != null) {
      MetadataState metadataState =
          InstrumentationContext.get(Metadata.class, MetadataState.class).get(consumerMetadata);
      clusterId = metadataState != null ? metadataState.clusterId : null;
    }
    KafkaConfigHelper.reportConsumerGroupMember(
        clusterId, consumerGroup, memberId, generationId, memberProtocol);
  }

  public static void muzzleCheck(ConsumerRecord record) {
    // Match ConsumerCoordinatorAdvice: only apply for kafka versions with headers
    record.headers();
  }
}
