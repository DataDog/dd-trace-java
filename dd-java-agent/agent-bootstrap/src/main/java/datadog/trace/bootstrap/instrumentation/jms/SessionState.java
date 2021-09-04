package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Holds message spans consumed in client-acknowledged or transacted sessions. It also assigns ids
 * to batches of messages produced from transacted sessions. This class needs to be thread-safe as
 * some JMS providers allow concurrent transactions.
 */
public final class SessionState {

  private static final AtomicReferenceFieldUpdater<SessionState, MessageBatchState> BATCH_STATE =
      AtomicReferenceFieldUpdater.newUpdater(
          SessionState.class, MessageBatchState.class, "batchState");
  private static final AtomicIntegerFieldUpdater<SessionState> COMMIT_SEQUENCE =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "commitSequence");

  private static final AtomicIntegerFieldUpdater<SessionState> ACTIVE_SCOPE_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "activeScopeCount");
  private static final AtomicIntegerFieldUpdater<SessionState> TIME_IN_QUEUE_SPAN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "timeInQueueSpanCount");

  // hard bound at 8192 captured spans, degrade to finishing spans early
  // if transactions are very large, rather than use lots of space
  static final int MAX_CAPTURED_SPANS = 8192;

  private final int ackMode;

  // producer-related session state
  private volatile MessageBatchState batchState;
  private volatile int commitSequence;

  // consumer-related session state
  private final Deque<AgentSpan> capturedSpans = new ArrayDeque<>();
  private final Map<Thread, AgentScope> activeScopes = new ConcurrentHashMap<>();
  private final Map<Thread, TimeInQueue> timeInQueueSpans;
  private volatile int activeScopeCount = 0;
  private volatile int timeInQueueSpanCount = 0;

  // this field is protected by synchronization of capturedSpans, but SpotBugs miss that
  @SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
  private boolean capturingFlipped = false;

  public SessionState(int ackMode) {
    this.ackMode = ackMode;
    if (Config.get().isJmsLegacyTracingEnabled()) {
      this.timeInQueueSpans = Collections.emptyMap();
    } else {
      this.timeInQueueSpans = new ConcurrentHashMap<>();
    }
  }

  public boolean isTransactedSession() {
    return ackMode == 0; /* Session.SESSION_TRANSACTED */
  }

  public boolean isClientAcknowledge() {
    return ackMode == 2; /* Session.CLIENT_ACKNOWLEDGE */
  }

  public boolean isAutoAcknowledge() {
    return ackMode != 0 && ackMode != 2; /* treat all other modes as Session.AUTO_ACKNOWLEDGE */

    // We can't be sure of the ack-pattern for non-standard vendor modes, so the safest thing
    // to do is close+finish message spans on the next receive like we do for AUTO_ACKNOWLEDGE
  }

  /** Retrieves details about the current message batch being produced in this session. */
  MessageBatchState currentBatchState() {
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
    synchronized (capturedSpans) {
      return capturedSpans.size();
    }
  }

  /** Finishes the given message span when a message from the same session is acknowledged. */
  public void finishOnAcknowledge(AgentSpan span) {
    captureMessageSpan(span);
  }

  /** Finishes the given message span when the session is committed, rolled back, or closed. */
  public void finishOnCommit(AgentSpan span) {
    captureMessageSpan(span);
  }

  private void captureMessageSpan(AgentSpan span) {
    synchronized (capturedSpans) {
      if (capturedSpans.size() < MAX_CAPTURED_SPANS) {
        // change capture direction of the deque on each commit/ack
        // avoids mixing new spans with the old group while still supporting LIFO
        if (capturingFlipped) {
          capturedSpans.addFirst(span);
        } else {
          capturedSpans.addLast(span);
        }
        return;
      }
    }
    // just finish the span to avoid an unbounded queue
    span.finish();
  }

  public void onAcknowledge() {
    finishCapturedSpans();
  }

  public void onCommitOrRollback() {
    COMMIT_SEQUENCE.incrementAndGet(this);
    finishCapturedSpans();
  }

  private void finishCapturedSpans() {
    // make sure we complete this before any subsequent commit/rollback/ack/close
    synchronized (this) {
      Iterator<AgentScope> activeScopeIterator = activeScopes.values().iterator();
      Iterator<TimeInQueue> timeInQueueIterator = timeInQueueSpans.values().iterator();

      // first close any session-related scopes that are still open
      while (activeScopeIterator.hasNext()) {
        maybeCloseScope(activeScopeIterator.next());
        activeScopeIterator.remove();
      }

      // next finish any message spans captured during this session
      int spansToFinish;
      boolean finishingFlipped;
      synchronized (capturedSpans) {
        spansToFinish = capturedSpans.size();
        // if capturing was flipped for this group then we need to flip finishing to match
        finishingFlipped = capturingFlipped;
        // update capturing to use the other end of the deque for the next group of spans
        capturingFlipped = !finishingFlipped;
      }
      for (int i = 0; i < spansToFinish; ++i) {
        AgentSpan span;
        synchronized (capturedSpans) {
          // finish spans in LIFO order according to how they were captured
          // for example addFirst --> pollFirst vs. addLast --> pollLast
          if (finishingFlipped) {
            span = capturedSpans.pollFirst();
          } else {
            span = capturedSpans.pollLast();
          }
        }
        // it won't be null, but just in case...
        if (null != span) {
          span.finish();
        }
      }

      // lastly finish any time-in-queue parent spans for this session
      while (timeInQueueIterator.hasNext()) {
        maybeFinishTimeInQueueSpan(timeInQueueIterator.next());
        timeInQueueIterator.remove();
      }
    }
  }

  /** Closes any active message scopes and finishes any pending client-ack/transacted spans. */
  public void onClose() {
    finishCapturedSpans();
  }

  /** Closes the given message scope when the next message is consumed or the session is closed. */
  void closeOnIteration(AgentScope newScope) {
    if (ACTIVE_SCOPE_COUNT.incrementAndGet(this) > 100) {
      closeStaleScopes();
    }
    maybeCloseScope(activeScopes.put(Thread.currentThread(), newScope));
  }

  /** Closes the scope previously registered by closeOnIteration, assumes same calling thread. */
  void closePreviousMessageScope() {
    maybeCloseScope(activeScopes.remove(Thread.currentThread()));
  }

  private void maybeCloseScope(AgentScope scope) {
    if (null != scope) {
      ACTIVE_SCOPE_COUNT.decrementAndGet(this);
      scope.close();
      if (isAutoAcknowledge()) {
        scope.span().finish();
      }
    }
  }

  private void closeStaleScopes() {
    Iterator<Map.Entry<Thread, AgentScope>> itr = activeScopes.entrySet().iterator();
    while (itr.hasNext()) {
      Map.Entry<Thread, AgentScope> entry = itr.next();
      if (!entry.getKey().isAlive()) {
        maybeCloseScope(entry.getValue());
        itr.remove();
      }
    }
  }

  /** Gets the current time-in-queue span; returns {@code null} if this is a new batch. */
  AgentSpan getTimeInQueueSpan(long batchId) {
    TimeInQueue holder = timeInQueueSpans.get(Thread.currentThread());
    if (null != holder) {
      // maintain time-in-queue for messages in same batch or same client-ack/transacted session
      if ((batchId > 0 && batchId == holder.batchId) || !isAutoAcknowledge()) {
        return holder.span;
      }
      // finish the old time-in-queue span and remove it before we create the next one
      finishTimeInQueueSpan(true);
    }
    return null;
  }

  /** Starts tracking a new time-in-queue span. */
  void setTimeInQueueSpan(long batchId, AgentSpan span) {
    if (TIME_IN_QUEUE_SPAN_COUNT.incrementAndGet(this) > 100) {
      finishStaleTimeInQueueSpans();
    }
    // getTimeInQueueSpan will have already removed the time-in-queue span for the previous batch
    timeInQueueSpans.put(Thread.currentThread(), new TimeInQueue(batchId, span));
  }

  /** Finishes the current time-in-queue span and optionally stops tracking it. */
  void finishTimeInQueueSpan(boolean clear) {
    if (clear) {
      maybeFinishTimeInQueueSpan(timeInQueueSpans.remove(Thread.currentThread()));
    } else {
      // finish time-in-queue span, but keep tracking it so messages in same batch can link to it
      // (called in AUTO_ACKNOWLEDGE mode to avoid leaving time-in-queue spans open indefinitely)
      TimeInQueue holder = timeInQueueSpans.get(Thread.currentThread());
      if (null != holder) {
        holder.span.finish();
      }
    }
  }

  private void maybeFinishTimeInQueueSpan(TimeInQueue holder) {
    if (null != holder) {
      TIME_IN_QUEUE_SPAN_COUNT.decrementAndGet(this);
      holder.span.finish();
    }
  }

  private void finishStaleTimeInQueueSpans() {
    Iterator<Map.Entry<Thread, TimeInQueue>> itr = timeInQueueSpans.entrySet().iterator();
    while (itr.hasNext()) {
      Map.Entry<Thread, TimeInQueue> entry = itr.next();
      if (!entry.getKey().isAlive()) {
        maybeFinishTimeInQueueSpan(entry.getValue());
        itr.remove();
      }
    }
  }
}
