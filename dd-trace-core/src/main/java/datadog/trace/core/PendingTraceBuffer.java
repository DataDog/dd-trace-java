package datadog.trace.core;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_MONITOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import java.util.concurrent.TimeUnit;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

public abstract class PendingTraceBuffer implements AutoCloseable {
  private static final int BUFFER_SIZE = 1 << 12; // 4096

  private static class DelayingPendingTraceBuffer extends PendingTraceBuffer {
    private static final long FORCE_SEND_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long SEND_DELAY_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long SLEEP_TIME_MS = 100;

    private final MpscBlockingConsumerArrayQueue<PendingTrace> queue;
    private final Thread worker;

    private volatile boolean closed = false;

    /** if the queue is full, pendingTrace trace will be written immediately. */
    public void enqueue(PendingTrace pendingTrace) {
      if (!queue.offer(pendingTrace)) {
        // Queue is full, so we can't buffer this trace, write it out directly instead.
        pendingTrace.write();
      }
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

    public void flush() {
      queue.drain(WriteDrain.WRITE_DRAIN);
    }

    private static final class WriteDrain implements MessagePassingQueue.Consumer<PendingTrace> {
      private static final WriteDrain WRITE_DRAIN = new WriteDrain();

      @Override
      public void accept(PendingTrace pendingTrace) {
        pendingTrace.write();
      }
    }

    private final class Worker implements Runnable {

      @Override
      public void run() {
        try {
          while (!closed && !Thread.currentThread().isInterrupted()) {

            PendingTrace pendingTrace = queue.take(); // block until available.

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
              // Trace is too new.  Requeue it and sleep to avoid a hot loop.
              enqueue(pendingTrace);
              Thread.sleep(SLEEP_TIME_MS);
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    public DelayingPendingTraceBuffer(int bufferSize) {
      this.queue = new MpscBlockingConsumerArrayQueue<>(bufferSize);
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
    public void enqueue(PendingTrace pendingTrace) {}
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

  public abstract void enqueue(PendingTrace pendingTrace);
}
