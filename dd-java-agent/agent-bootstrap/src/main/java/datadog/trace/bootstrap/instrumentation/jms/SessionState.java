package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * This is a holder for spans created in a transacted session. It needs to be thread-safe since some
 * JMS providers allow concurrent transactions.
 */
public final class SessionState {

  private static final AtomicReferenceFieldUpdater<SessionState, MessageBatchState> BATCH_STATE =
      AtomicReferenceFieldUpdater.newUpdater(
          SessionState.class, MessageBatchState.class, "batchState");
  private static final AtomicIntegerFieldUpdater<SessionState> COMMIT_SEQUENCE =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "commitSequence");
  private static final AtomicReferenceFieldUpdater<SessionState, Queue> CAPTURED_SPANS =
      AtomicReferenceFieldUpdater.newUpdater(SessionState.class, Queue.class, "capturedSpans");
  private static final AtomicIntegerFieldUpdater<SessionState> SPAN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "spanCount");

  // hard bound at 8192 captured spans, degrade to finishing spans early
  // if transactions are very large, rather than use lots of space
  static final int MAX_CAPTURED_SPANS = 8192;

  private final int ackMode;

  // transactional producer state
  private volatile MessageBatchState batchState;
  private volatile int commitSequence;

  // transactional consumer state
  private volatile Queue<AgentSpan> capturedSpans;
  private volatile int spanCount;

  public SessionState(int ackMode) {
    this.ackMode = ackMode;
    // defer creating capturedSpans queue as we only need it for consumer sessions
  }

  public boolean isTransactedSession() {
    return ackMode == 0; /* Session.SESSION_TRANSACTED */
  }

  public boolean isAutoAcknowledge() {
    return ackMode == 1 || ackMode == 3; /* Session.AUTO_ACKNOWLEDGE, Session.DUPS_OK_ACKNOWLEDGE */
  }

  public boolean isClientAcknowledge() {
    return ackMode == 2; /* Session.CLIENT_ACKNOWLEDGE */
  }

  public MessageBatchState currentBatchState() {
    MessageBatchState oldBatch = batchState;
    if (null != oldBatch && oldBatch.commitSequence == commitSequence) {
      return oldBatch;
    }
    MessageBatchState newBatch = new MessageBatchState(commitSequence);
    if (!BATCH_STATE.compareAndSet(this, oldBatch, newBatch)) {
      newBatch = batchState;
    }
    return newBatch;
  }

  // only used for testing
  int getCapturedSpanCount() {
    Queue<AgentSpan> q = capturedSpans;
    assert spanCount == (null != q ? q.size() : 0);
    return spanCount;
  }

  /** Finishes the given message span when a message from the session is acknowledged. */
  public void finishOnAcknowledge(AgentSpan span) {
    captureMessageSpan(span);
  }

  /** Finishes the given message span when the session is committed, rolled back, or closed. */
  public void finishOnCommit(AgentSpan span) {
    captureMessageSpan(span);
  }

  private void captureMessageSpan(AgentSpan span) {
    Queue<AgentSpan> q = capturedSpans;
    if (null == q) {
      q = new ArrayBlockingQueue<AgentSpan>(MAX_CAPTURED_SPANS);
      if (!CAPTURED_SPANS.compareAndSet(this, null, q)) {
        q = capturedSpans; // another thread won, use their value
      }
    }
    if (q.offer(span)) {
      SPAN_COUNT.incrementAndGet(this);
    } else {
      // just finish the span to avoid an unbounded queue
      span.finish();
    }
  }

  public void onCommit() { // also called on rollback or close
    COMMIT_SEQUENCE.incrementAndGet(this);
    finishCapturedSpans();
  }

  public void onAcknowledge() {
    finishCapturedSpans();
  }

  private void finishCapturedSpans() {
    Queue<AgentSpan> q = capturedSpans;
    if (null != q) {
      synchronized (this) {
        // synchronized in case incoming requests
        // happen quicker than we can close the spans
        int taken = SPAN_COUNT.get(this);
        for (int i = 0; i < taken; ++i) {
          AgentSpan span = q.poll();
          // it won't be null, but just in case...
          if (null != span) {
            span.finish();
          }
        }
        SPAN_COUNT.getAndAdd(this, -taken);
      }
    }
  }
}
