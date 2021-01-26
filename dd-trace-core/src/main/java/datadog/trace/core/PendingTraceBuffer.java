package datadog.trace.core;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_MONITOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.jctools.queues.SpscArrayQueue;

public abstract class PendingTraceBuffer implements AutoCloseable {
  private static final int BUFFER_SIZE = 1 << 12; // 4096

  public interface Element {
    long oldestFinishedTime();

    boolean lastReferencedNanosAgo(long nanos);

    void write();
  }

  private static class DelayingPendingTraceBuffer extends PendingTraceBuffer {
    private static final long FORCE_SEND_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long SEND_DELAY_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long SLEEP_TIME_MS = 100;

    private final MpscBlockingConsumerArrayQueue<Element> queue;
    // Yes, this is a bit of an overkill, but it's easier to use the same APIs
    private final SpscArrayQueue<Element> requeue;
    private long firstRequeueTimeNanos = 0;
    private final Thread worker;

    private volatile boolean closed = false;
    private final AtomicInteger flushCounter = new AtomicInteger(0);
    private final TransferDrain TRANSFER_DRAIN = new TransferDrain();

    /** if the queue is full, pendingTrace trace will be written immediately. */
    public void enqueue(Element pendingTrace) {
      enqueue(queue, pendingTrace);
    }

    private boolean enqueue(MessagePassingQueue<Element> queue, Element pendingTrace) {
      boolean added = queue.offer(pendingTrace);
      if (!added) {
        // Queue is full, so we can't buffer this trace, write it out directly instead.
        pendingTrace.write();
      }
      return added;
    }

    public void start() {
      worker.start();
    }

    @Override
    public void close() {
      closed = true;
      worker.interrupt();
      try {
        worker.join(THREAD_JOIN_TIMOUT_MS);
      } catch (InterruptedException ignored) {
      }
    }

    // Only used from within tests
    public void flush() {
      if (worker.isAlive()) {
        int count = flushCounter.get();
        boolean signaled;
        do {
          signaled = queue.offer(FlushElement.FLUSH_ELEMENT);
          Thread.yield();
        } while (!closed && !signaled);
        int newCount;
        do {
          newCount = flushCounter.get();
          Thread.yield();
        } while (!closed && count >= newCount);
      }
    }

    private static final class WriteDrain implements MessagePassingQueue.Consumer<Element> {
      private static final WriteDrain WRITE_DRAIN = new WriteDrain();

      @Override
      public void accept(Element pendingTrace) {
        pendingTrace.write();
      }
    }

    private final class TransferDrain implements MessagePassingQueue.Consumer<Element> {
      @Override
      public void accept(Element pendingTrace) {
        enqueue(pendingTrace);
      }
    }

    private static final class FlushElement implements Element {
      static FlushElement FLUSH_ELEMENT = new FlushElement();

      @Override
      public long oldestFinishedTime() {
        return 0;
      }

      @Override
      public boolean lastReferencedNanosAgo(long nanos) {
        return false;
      }

      @Override
      public void write() {}
    }

    private final class Worker implements Runnable {

      @Override
      public void run() {
        try {
          while (!closed && !Thread.currentThread().isInterrupted()) {
            long sleepTimeMillis = 0;
            if (firstRequeueTimeNanos != 0) {
              // Have at least one trace been requeued for more than SLEEP_TIME_MILLIS?
              sleepTimeMillis =
                  SLEEP_TIME_MS
                      - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstRequeueTimeNanos);
              if (sleepTimeMillis <= 0) {
                firstRequeueTimeNanos = 0;
                // Move all the traces back to the normal queue
                requeue.drain(TRANSFER_DRAIN);
              }
            }
            Element pendingTrace = queue.peek();
            if (sleepTimeMillis > 0) {
              // If we had something in the requeue but it hadn't been there long enough, then
              // block at most the remaining time before checking again
              pendingTrace = queue.poll(sleepTimeMillis, TimeUnit.MILLISECONDS);
            } else {
              // If we had something in the requeue and transferred it to the normal queue, or if
              // there was nothing in the requeue, then just take the next element from the normal
              // queue, potentially blocking
              pendingTrace = queue.poll();
            }
            if (null == pendingTrace) {
              continue;
            }

            if (pendingTrace instanceof FlushElement) {
              // Since this is an MPSC queue, the drain needs to be called on the consumer thread
              queue.drain(WriteDrain.WRITE_DRAIN);
              requeue.drain(WriteDrain.WRITE_DRAIN);
              flushCounter.incrementAndGet();
              continue;
            }

            long oldestFinishedTime = pendingTrace.oldestFinishedTime();

            long finishTimestampMillis = TimeUnit.NANOSECONDS.toMillis(oldestFinishedTime);
            if (finishTimestampMillis <= System.currentTimeMillis() - FORCE_SEND_DELAY_MS) {
              // Root span is getting old. Send the trace to avoid being discarded by agent.
              pendingTrace.write();
              continue;
            }

            if (pendingTrace.lastReferencedNanosAgo(SEND_DELAY_NS)) {
              // Trace has been unmodified long enough, go ahead and write whatever is finished.
              pendingTrace.write();
            } else {
              // Trace is too new.  Requeue it and check it later.
              if (enqueue(requeue, pendingTrace) && firstRequeueTimeNanos == 0) {
                // Add a time stamp so we know when to put back elements in the normal queue
                firstRequeueTimeNanos = System.nanoTime();
              }
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        closed = true;
      }
    }

    public DelayingPendingTraceBuffer(int bufferSize) {
      this.queue = new MpscBlockingConsumerArrayQueue<>(bufferSize);
      this.requeue = new SpscArrayQueue<>(bufferSize);
      this.worker = newAgentThread(TRACE_MONITOR, new Worker());
    }
  }

  static class MutePendingTraceBuffer extends PendingTraceBuffer {
    @Override
    public void start() {}

    @Override
    public void close() {}

    @Override
    public void flush() {}

    @Override
    public void enqueue(Element pendingTrace) {}
  }

  public static PendingTraceBuffer delaying() {
    return new DelayingPendingTraceBuffer(BUFFER_SIZE);
  }

  public static PendingTraceBuffer mute() {
    return new MutePendingTraceBuffer();
  }

  public abstract void start();

  public abstract void close();

  public abstract void flush();

  public abstract void enqueue(Element pendingTrace);
}
