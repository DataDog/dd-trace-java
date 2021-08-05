package datadog.trace.bootstrap.instrumentation.jms;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class MessageProducerState {
  private static final long MAX_BATCH_DURATION_MILLIS = TimeUnit.SECONDS.toMillis(1);

  private static final AtomicReferenceFieldUpdater<MessageProducerState, MessageBatchState>
      BATCH_STATE =
          AtomicReferenceFieldUpdater.newUpdater(
              MessageProducerState.class, MessageBatchState.class, "batchState");

  private final SessionState sessionState;
  private final int ackMode;

  private volatile MessageBatchState batchState;

  public MessageProducerState(SessionState sessionState, int ackMode) {
    this.sessionState = sessionState;
    this.ackMode = ackMode;
  }

  public SessionState getSessionState() {
    return sessionState;
  }

  public MessageBatchState currentBatchState() {
    MessageBatchState oldBatch = batchState;
    if (null != oldBatch && !isStale(oldBatch)) {
      return oldBatch;
    }
    MessageBatchState newBatch = new MessageBatchState(currentContextId());
    if (!BATCH_STATE.compareAndSet(this, oldBatch, newBatch)) {
      newBatch = batchState;
    }
    return newBatch;
  }

  private boolean isStale(MessageBatchState batchState) {
    return batchState.contextId != currentContextId()
        || (ackMode != 0
            && System.currentTimeMillis() - batchState.startMillis > MAX_BATCH_DURATION_MILLIS);
  }

  private long currentContextId() {
    return ackMode == 0 ? sessionState.currentCommitId() : Thread.currentThread().getId();
  }
}
