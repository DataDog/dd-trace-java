package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.api.Config;
import datadog.trace.api.IdGenerationStrategy;

/**
 * Holds time-in-queue related information about a batch of messages.
 */
public final class MessageBatchState {
  private static final IdGenerationStrategy ID_STRATEGY = Config.get().getIdGenerationStrategy();
  final long batchId;
  final long startMillis;
  // used to decide when to assign a new batch state
  final int commitSequence;

  MessageBatchState(int commitSequence) {
    this.batchId = ID_STRATEGY.generateTraceId().toLong();
    this.startMillis = System.currentTimeMillis();
    this.commitSequence = commitSequence;
  }

  public long getBatchId() {
    return batchId;
  }

  public long getStartMillis() {
    return startMillis;
  }
}
