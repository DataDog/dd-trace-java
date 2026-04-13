package datadog.trace.instrumentation.kafka_common;

import java.util.concurrent.atomic.AtomicReference;

/** Composite state attached to Kafka Metadata objects via contextStore. */
public class MetadataState {
  public volatile String clusterId;
  private final AtomicReference<PendingConfig> pendingConfig = new AtomicReference<>();

  public void setPendingConfig(PendingConfig config) {
    pendingConfig.set(config);
  }

  /** Atomically retrieves and clears the pending config. */
  public PendingConfig takePendingConfig() {
    return pendingConfig.getAndSet(null);
  }
}
