package datadog.trace.instrumentation.kafka_clients;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import org.apache.kafka.clients.Metadata;

public class KafkaConsumerInstrumentationHelper {

  /** Whether the last record's consume span should be kept active past the poll loop. */
  public static boolean shouldDeferConsumerScope() {
    return Config.get().isKafkaCreateConsumerScopeEnabled();
  }

  /**
   * Finishes the {@code kafka.consume} span deliberately left active past the poll loop when
   * consumer-scope deferral is enabled, restoring the caller's context. Safe to call from any
   * consumer entry point (poll, close, unsubscribe).
   */
  public static void closeLingeringConsumeScope(KafkaConsumerInfo info) {
    if (!shouldDeferConsumerScope()) {
      return;
    }
    // restore the caller's context when the lingering consume span is still active on this thread
    AgentSpan active = AgentTracer.activeSpan();
    if (active != null && KafkaDecorator.KAFKA_CONSUME.equals(active.getOperationName())) {
      if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
        AgentTracer.closeLingeringIterationScope();
      } else {
        AgentSpan swappedOut = AgentSpan.fromContext(Context.root().swap());
        if (swappedOut != null) {
          swappedOut.finishWithEndToEnd();
        }
      }
    }
    // owner-aware: finish the exact deferred span regardless of thread/context (CAS-safe if already
    // finished)
    if (info != null) {
      AgentSpan deferred = info.getAndClearDeferredConsumeSpan();
      if (deferred != null) {
        deferred.finishWithEndToEnd();
      }
    }
  }

  public static String extractGroup(KafkaConsumerInfo kafkaConsumerInfo) {
    if (kafkaConsumerInfo != null) {
      return kafkaConsumerInfo.getConsumerGroup();
    }
    return null;
  }

  public static String extractClusterId(
      KafkaConsumerInfo kafkaConsumerInfo,
      ContextStore<Metadata, MetadataState> metadataContextStore) {
    if (kafkaConsumerInfo != null) {
      Metadata consumerMetadata = kafkaConsumerInfo.getClientMetadata();
      if (consumerMetadata != null) {
        MetadataState state = metadataContextStore.get(consumerMetadata);
        return state != null ? state.clusterId : null;
      }
    }
    return null;
  }

  public static String extractBootstrapServers(KafkaConsumerInfo kafkaConsumerInfo) {
    return kafkaConsumerInfo == null ? null : kafkaConsumerInfo.getBootstrapServers();
  }
}
