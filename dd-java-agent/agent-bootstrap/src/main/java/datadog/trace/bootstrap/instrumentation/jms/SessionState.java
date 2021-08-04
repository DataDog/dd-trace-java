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

  private static final AtomicReferenceFieldUpdater<SessionState, Queue> CAPTURED_SPANS =
      AtomicReferenceFieldUpdater.newUpdater(SessionState.class, Queue.class, "capturedSpans");
  private static final AtomicIntegerFieldUpdater<SessionState> SPAN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "spanCount");

  // hard bound at 8192 captured spans, degrade to finishing spans early
  // if transactions are very large, rather than use lots of space
  static final int CAPACITY = 8192;
  private volatile Queue<AgentSpan> capturedSpans;
  private volatile int spanCount;

  // only used for testing
  boolean isEmpty() {
    Queue<AgentSpan> q = capturedSpans;
    return null == q || q.isEmpty();
  }

  public void capture(AgentSpan span) {
    Queue<AgentSpan> q = capturedSpans;
    if (null == q) {
      q = new ArrayBlockingQueue<AgentSpan>(CAPACITY);
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

  public void onCommit() {
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
}
