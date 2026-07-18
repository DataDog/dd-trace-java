package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInstrumentationHelper {

  /** Deferring the consumer scope is only supported under the legacy context manager. */
  public static boolean shouldDeferConsumerScope() {
    return Config.get().isKafkaCreateConsumerScopeEnabled()
        && InstrumenterConfig.get().isLegacyContextManagerEnabled();
  }

  public static String extractGroup(KafkaConsumerInfo kafkaConsumerInfo) {
    if (kafkaConsumerInfo != null) {
      return kafkaConsumerInfo.getConsumerGroup().get();
    }
    return null;
  }

  public static String extractClusterId(
      KafkaConsumerInfo kafkaConsumerInfo,
      ContextStore<Metadata, MetadataState> metadataContextStore) {
    if (kafkaConsumerInfo != null) {
      Metadata metadata = kafkaConsumerInfo.getmetadata().get();
      if (metadata != null) {
        MetadataState state = metadataContextStore.get(metadata);
        return state != null ? state.clusterId : null;
      }
    }
    return null;
  }

  public static String extractBootstrapServers(KafkaConsumerInfo kafkaConsumerInfo) {
    return kafkaConsumerInfo == null ? null : kafkaConsumerInfo.getBootstrapServers().get();
  }

  /**
   * Finishes the {@code kafka.consume} span deliberately left active past the poll loop when
   * consumer-scope deferral is enabled. Safe to call from any consumer entry point (poll, close,
   * unsubscribe).
   */
  public static void closeLingeringConsumeScope(KafkaConsumerInfo info) {
    if (!shouldDeferConsumerScope()) {
      return;
    }
    // clean same-thread pop when the lingering scope is still top-of-stack (restores context)
    AgentSpan active = AgentTracer.activeSpan();
    if (active != null && KafkaDecorator.KAFKA_CONSUME.equals(active.getOperationName())) {
      AgentTracer.closeLingeringIterationScope();
    }
    // owner-aware: finish the exact deferred span regardless of thread/stack (CAS-safe if already
    // finished)
    if (info != null) {
      AgentSpan deferred = info.getAndClearDeferredConsumeSpan();
      if (deferred != null) {
        deferred.finishWithEndToEnd();
      }
    }
  }
}
