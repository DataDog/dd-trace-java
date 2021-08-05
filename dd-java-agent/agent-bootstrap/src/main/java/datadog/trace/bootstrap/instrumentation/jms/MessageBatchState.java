package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.api.Config;
import datadog.trace.api.IdGenerationStrategy;

public final class MessageBatchState {
  private static final IdGenerationStrategy ID_STRATEGY = Config.get().getIdGenerationStrategy();

  final long batchId;
  final long startMillis;
  final long contextId;

  MessageBatchState(long contextId) {
    this.batchId = ID_STRATEGY.generate().toLong();
    this.startMillis = System.currentTimeMillis();
    this.contextId = contextId;
  }
}
