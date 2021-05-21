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

  // hard bound at 8192 pending spans, degrade to finishing spans early
  // if transactions are very large, rather than use lots of space
  private final Queue<AgentSpan> queue;
  private final boolean transacted;
  private static final AtomicIntegerFieldUpdater<SessionState> SEQUENCE =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "sequence");
  private volatile int sequence;

  public SessionState(boolean transacted) {
    this.queue = transacted ? new ArrayBlockingQueue<AgentSpan>(CAPACITY) : null;
    this.transacted = transacted;
  }

  boolean isEmpty() {
    return !transacted || queue.isEmpty();
  }

  public void add(AgentSpan span) {
    if (transacted) {
      if (queue.offer(span)) {
        SEQUENCE.incrementAndGet(this);
      } else {
        // just finish the span to avoid an unbounded queue
        span.finish();
      }
    }
  }

  public void onCommit() {
    if (transacted) {
      synchronized (this) {
        // synchronized in case the second commit
        // happens quicker than we can close the spans
        int taken = SEQUENCE.get(this);
        for (int i = 0; i < taken; ++i) {
          AgentSpan span = queue.poll();
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
