package datadog.trace.instrumentation.kafka_common;

/** Holds a consumer group membership until Data Streams is enabled and the cluster ID is known. */
public class PendingConsumerGroupMember {
  public final String consumerGroup;
  public final String memberId;
  public final int generationId;
  public final String memberProtocol;

  public PendingConsumerGroupMember(
      String consumerGroup, String memberId, int generationId, String memberProtocol) {
    this.consumerGroup = consumerGroup;
    this.memberId = memberId;
    this.generationId = generationId;
    this.memberProtocol = memberProtocol;
  }
}
