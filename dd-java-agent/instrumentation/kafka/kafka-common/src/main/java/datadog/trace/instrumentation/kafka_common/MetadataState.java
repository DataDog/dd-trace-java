package datadog.trace.instrumentation.kafka_common;

import java.util.concurrent.atomic.AtomicReference;

/** Composite state attached to Kafka Metadata objects via contextStore. */
public class MetadataState {
  public volatile String clusterId;
  private final AtomicReference<PendingConfig> pendingConfig = new AtomicReference<>();
  private final AtomicReference<PendingConsumerGroupMember> pendingConsumerGroupMember =
      new AtomicReference<>();

  public void setPendingConfig(PendingConfig config) {
    pendingConfig.set(config);
  }

  /** Whether a config is pending. */
  public boolean hasPendingConfig() {
    return pendingConfig.get() != null;
  }

  /** Sets the pending config only if none is pending; returns whether it was set. */
  public boolean setPendingConfigIfAbsent(PendingConfig config) {
    return pendingConfig.compareAndSet(null, config);
  }

  /** Atomically retrieves and clears the pending config. */
  public PendingConfig takePendingConfig() {
    return pendingConfig.getAndSet(null);
  }

  /** Replaces any pending consumer group membership with a newer one. */
  public void setPendingConsumerGroupMember(PendingConsumerGroupMember member) {
    pendingConsumerGroupMember.set(member);
  }

  /** Atomically retrieves and clears the pending consumer group membership. */
  public PendingConsumerGroupMember takePendingConsumerGroupMember() {
    return pendingConsumerGroupMember.getAndSet(null);
  }
}
