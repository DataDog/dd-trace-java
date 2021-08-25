package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * This is a holder for spans created in a transacted session. It needs to be thread-safe since some
 * JMS providers allow concurrent transactions.
 */
public final class SessionState {

  private static final AtomicIntegerFieldUpdater<SessionState> SCOPE_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "scopeCount");

  private static final AtomicReferenceFieldUpdater<SessionState, Queue> CAPTURED_SPANS =
      AtomicReferenceFieldUpdater.newUpdater(SessionState.class, Queue.class, "capturedSpans");
  private static final AtomicIntegerFieldUpdater<SessionState> SPAN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "spanCount");

  // hard bound at 8192 captured spans, degrade to finishing spans early
  // if transactions are very large, rather than use lots of space
  static final int MAX_CAPTURED_SPANS = 8192;

  private final int ackMode;

  // consumer-related session state
  private final Map<Thread, AgentScope> activeScopes = new ConcurrentHashMap<>();
  private volatile int scopeCount;
  private volatile Queue<AgentSpan> capturedSpans;
  private volatile int spanCount;

  public SessionState(int ackMode) {
    this.ackMode = ackMode;
    // defer creating queue as we only need it for transacted sessions
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

  // only used for testing
  int getCapturedSpanCount() {
    Queue<AgentSpan> q = capturedSpans;
    assert spanCount == (null != q ? q.size() : 0);
    return spanCount;
  }

  /** Finishes the given message span when the session is committed, rolled back, or closed. */
  public void finishOnCommit(AgentSpan span) {
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

  public void onCommitOrRollback() {
    Queue<AgentSpan> q = capturedSpans;
    if (null != q) {
      synchronized (this) {
        // synchronized in case the second commit
        // happens quicker than we can close the spans
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

  /** Closes the given message scope when the next message is consumed or the session is closed. */
  void closeOnIteration(AgentScope newScope) {
    if (SCOPE_COUNT.incrementAndGet(this) > 100) {
      closeStaleScopes();
    }
    maybeCloseScope(activeScopes.put(Thread.currentThread(), newScope));
  }

  /** Closes the scope previously registered by closeOnIteration, assumes same calling thread. */
  void closePreviousMessageScope() {
    maybeCloseScope(activeScopes.remove(Thread.currentThread()));
  }

  /** Closes any active message scopes and finishes any pending transacted spans. */
  public void onClose() {
    for (AgentScope scope : activeScopes.values()) {
      maybeCloseScope(scope);
    }
    activeScopes.clear();
    if (isTransactedSession()) {
      onCommitOrRollback(); // implicit rollback of any active transaction
    }
  }

  private void maybeCloseScope(AgentScope scope) {
    if (null != scope) {
      SCOPE_COUNT.decrementAndGet(this);
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
}
