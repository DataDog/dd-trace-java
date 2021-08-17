package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * This is a holder for spans created in a transacted session. It needs to be thread-safe since some
 * JMS providers allow concurrent transactions.
 */
public final class SessionState {

  public static final int CAPACITY = 8192;

  private final int ackMode;

  // hard bound at 8192 pending spans, degrade to finishing spans early
  // if transactions are very large, rather than use lots of space
  private volatile Queue<AgentSpan> queue;
  private static final AtomicIntegerFieldUpdater<SessionState> SEQUENCE =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "sequence");
  private volatile int sequence;

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
  boolean isEmpty() {
    Queue<AgentSpan> q = queue;
    return null == q || q.isEmpty();
  }

  public void add(AgentSpan span) {
    Queue<AgentSpan> q = queue;
    if (null == q) {
      synchronized (this) {
        if (null == queue) {
          queue = new ArrayBlockingQueue<AgentSpan>(CAPACITY);
        }
        q = queue;
      }
    }
    if (q.offer(span)) {
      SEQUENCE.incrementAndGet(this);
    } else {
      // just finish the span to avoid an unbounded queue
      span.finish();
    }
  }

  public void onCommit() {
    Queue<AgentSpan> q = queue;
    if (null != q) {
      synchronized (this) {
        // synchronized in case the second commit
        // happens quicker than we can close the spans
        int taken = SEQUENCE.get(this);
        for (int i = 0; i < taken; ++i) {
          AgentSpan span = q.poll();
          // it won't be null, but just in case...
          if (null != span) {
            span.finish();
          }
        }
        SEQUENCE.getAndAdd(this, -taken);
      }
    }
  }
}
