package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

  private static final AtomicIntegerFieldUpdater<SessionState> TIME_IN_QUEUE_SPAN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(SessionState.class, "timeInQueueSpanCount");

  private static final Comparator<Map.Entry<Thread, TimeInQueue>> YOUNGEST_TIME_IN_QUEUE_FIRST =
      new Comparator<Map.Entry<Thread, TimeInQueue>>() {
        @Override
        public int compare(Map.Entry<Thread, TimeInQueue> o1, Map.Entry<Thread, TimeInQueue> o2) {
          // reverse natural order to sort start time by largest first (ie. youngest)
          return Long.compare(o2.getValue().span.getStartTime(), o1.getValue().span.getStartTime());
        }
      };

  // hard bound of captured spans, degrade to finishing spans early
  // if transactions are very large, rather than use lots of space
  static final int MAX_CAPTURED_SPANS = 512;

  // mimic implicit message ack if oldest unacknowledged message span is too old
  static final long UNACKNOWLEDGED_MAX_AGE =
      TimeUnit.SECONDS.toMillis(Config.get().getJmsUnacknowledgedMaxAge());

  private static final int MAX_TRACKED_THREADS = 100;
  private static final int MIN_EVICTED_THREADS = 10;

  private final int ackMode;

  // producer-related session state
  private volatile MessageBatchState batchState;
  private volatile int commitSequence;

  // consumer-related session state
  private final Deque<AgentSpan> capturedSpans;
  private long oldestCaptureTime = 0;
  private final Map<Thread, TimeInQueue> timeInQueueSpans;
  private volatile int timeInQueueSpanCount = 0;

  // this field is protected by synchronization of capturedSpans, but SpotBugs miss that
  @SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
  private boolean capturingFlipped = false;

  public SessionState(int ackMode, boolean timeInQueueEnabled) {
    this.ackMode = ackMode;
    if (isAutoAcknowledge()) {
      this.capturedSpans = null; // unused in auto-ack
    } else {
      this.capturedSpans = new ArrayDeque<>();
    }
    if (timeInQueueEnabled) {
      this.timeInQueueSpans = new ConcurrentHashMap<>();
    } else {
      this.timeInQueueSpans = Collections.emptyMap();
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
    if (null == capturedSpans) {
      return 0;
    }
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
    if (null != capturedSpans) {
      synchronized (capturedSpans) {
        if (isClientAcknowledge() && implicitMessageAck()) {
          finishCapturedSpans(); // time-in-queues spans have a different clean-up mechanism
        }
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
    }
    // unable to capture span; finish it to avoid unbounded growth
    span.finish();
  }

  private boolean implicitMessageAck() {
    long now = System.currentTimeMillis();
    if (oldestCaptureTime == 0) {
      oldestCaptureTime = now; // will be cleared when finishCapturedSpans is called
      return false;
    }
    return (now - oldestCaptureTime) > UNACKNOWLEDGED_MAX_AGE;
  }

  public void onAcknowledgeOrRecover() {
    finishSessionSpans();
  }

  public void onCommitOrRollback() {
    COMMIT_SEQUENCE.incrementAndGet(this);
    finishSessionSpans();
  }

  private void finishSessionSpans() {
    // make sure we complete this before any subsequent commit/rollback/ack/close
    synchronized (this) {
      Iterator<TimeInQueue> timeInQueueIterator = timeInQueueSpans.values().iterator();

      // finish any message spans captured during this session
      if (null != capturedSpans) {
        finishCapturedSpans();
      }

      // finish any time-in-queue parent spans for this session
      while (timeInQueueIterator.hasNext()) {
        maybeFinishTimeInQueueSpan(timeInQueueIterator.next());
        timeInQueueIterator.remove();
      }
    }
  }

  private void finishCapturedSpans() {
    int spansToFinish;
    boolean finishingFlipped;
    synchronized (capturedSpans) {
      spansToFinish = capturedSpans.size();
      // if capturing was flipped for this group then we need to flip finishing to match
      finishingFlipped = capturingFlipped;
      // update capturing to use the other end of the deque for the next group of spans
      capturingFlipped = !finishingFlipped;
      oldestCaptureTime = 0;
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
  }

  /** Finishes any pending client-ack/transacted spans. */
  public void onClose() {
    finishSessionSpans();
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
    if (TIME_IN_QUEUE_SPAN_COUNT.incrementAndGet(this) > MAX_TRACKED_THREADS) {
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
    Queue<Map.Entry<Thread, TimeInQueue>> oldestEntries =
        new PriorityQueue<>(MIN_EVICTED_THREADS + 1, YOUNGEST_TIME_IN_QUEUE_FIRST);

    // first evict any time-in-queue spans linked to stopped threads
    int evictedThreads = 0;
    Iterator<Map.Entry<Thread, TimeInQueue>> itr = timeInQueueSpans.entrySet().iterator();
    while (itr.hasNext()) {
      Map.Entry<Thread, TimeInQueue> entry = itr.next();
      if (!entry.getKey().isAlive()) {
        evictedThreads++;
        maybeFinishTimeInQueueSpan(entry.getValue());
        itr.remove();
      } else if (evictedThreads < MIN_EVICTED_THREADS) {
        // not evicted enough yet - sort spans so far from youngest (head) to oldest (tail)
        oldestEntries.offer(entry);
        if (oldestEntries.size() > MIN_EVICTED_THREADS) {
          oldestEntries.poll(); // discard the youngest span to keep the oldest N spans
        }
      }
    }

    // didn't find enough stopped threads, so evict oldest N time-in-queue spans
    if (evictedThreads < MIN_EVICTED_THREADS) {
      for (Map.Entry<Thread, TimeInQueue> entry : oldestEntries) {
        if (timeInQueueSpans.remove(entry.getKey(), entry.getValue())) {
          maybeFinishTimeInQueueSpan(entry.getValue());
        }
      }
    }
  }
}
