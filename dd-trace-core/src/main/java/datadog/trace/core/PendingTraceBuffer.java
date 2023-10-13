package datadog.trace.core;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_MONITOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.time.TimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class PendingTraceBuffer implements AutoCloseable {
  private static final int BUFFER_SIZE = 1 << 12; // 4096

  public boolean longRunningSpansEnabled() {
    return false;
  }

  public interface Element {
    long oldestFinishedTime();

    boolean lastReferencedNanosAgo(long nanos);

    void write();

    DDSpan getRootSpan();

    /**
     * Set or clear if the {@code Element} is enqueued. Needs to be atomic.
     *
     * @param enqueued true if the enqueued state should be set or false if it should be cleared
     * @return true iff the enqueued value was changed from another value to the new value, false
     *     otherwise
     */
    boolean setEnqueued(boolean enqueued);

    boolean writeOnBufferFull();
  }

  private static class DelayingPendingTraceBuffer extends PendingTraceBuffer {
    private static final long FORCE_SEND_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long SEND_DELAY_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long SLEEP_TIME_MS = 100;

    private final MpscBlockingConsumerArrayQueue<Element> queue;
    private final Thread worker;
    private final TimeSource timeSource;

    private volatile boolean closed = false;
    private final AtomicInteger flushCounter = new AtomicInteger(0);

    private final LongRunningTracesTracker runningTracesTracker;

    public boolean longRunningSpansEnabled() {
      return runningTracesTracker != null;
    }

    @Override
    public void enqueue(Element pendingTrace) {
      if (pendingTrace.setEnqueued(true)) {
        if (!queue.offer(pendingTrace)) {
          // Mark it as not in the queue
          pendingTrace.setEnqueued(false);

          if (!pendingTrace.writeOnBufferFull()) {
            return;
          }
          pendingTrace.write();
        }
      }
    }

    @Override
    public void start() {
      worker.start();
    }

    @Override
    public void close() {
      flush();
      closed = true;
      worker.interrupt();
      try {
        worker.join(THREAD_JOIN_TIMOUT_MS);
      } catch (InterruptedException ignored) {
      }
    }

    private void yieldOrSleep(final int loop) {
      if (loop <= 3) {
        Thread.yield();
      } else {
        try {
          Thread.sleep(10);
        } catch (Throwable ignored) {
        }
      }
    }

    @Override
    public void flush() {
      if (worker.isAlive()) {
        int count = flushCounter.get();
        int loop = 1;
        boolean signaled = queue.offer(FlushElement.FLUSH_ELEMENT);
        while (!closed && !signaled) {
          yieldOrSleep(loop++);
          signaled = queue.offer(FlushElement.FLUSH_ELEMENT);
        }
        int newCount = flushCounter.get();
        while (!closed && count >= newCount) {
          yieldOrSleep(loop++);
          newCount = flushCounter.get();
        }
      }
    }

    private static final class WriteDrain implements MessagePassingQueue.Consumer<Element> {
      private static final WriteDrain WRITE_DRAIN = new WriteDrain();

      @Override
      public void accept(Element pendingTrace) {
        pendingTrace.write();
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

      @Override
      public DDSpan getRootSpan() {
        return null;
      }

      @Override
      public boolean setEnqueued(boolean enqueued) {
        return true;
      }

      @Override
      public boolean writeOnBufferFull() {
        return true;
      }
    }

    private final class Worker implements Runnable {

      @Override
      public void run() {
        try {
          while (!closed && !Thread.currentThread().isInterrupted()) {

            Element pendingTrace = null;
            if (longRunningSpansEnabled()) {
              pendingTrace = queue.poll(1, TimeUnit.SECONDS);
              runningTracesTracker.flushAndCompact(timeSource.getCurrentTimeMillis());
              if (pendingTrace == null) {
                continue;
              }
            } else {
              pendingTrace = queue.take(); // block until available;
            }

            if (pendingTrace instanceof FlushElement) {
              // Since this is an MPSC queue, the drain needs to be called on the consumer thread
              queue.drain(WriteDrain.WRITE_DRAIN);
              flushCounter.incrementAndGet();
              continue;
            }

            // The element is no longer in the queue
            pendingTrace.setEnqueued(false);

            if (longRunningSpansEnabled()) {
              if (runningTracesTracker.add(pendingTrace)) {
                continue;
              }
            }

            long oldestFinishedTime = pendingTrace.oldestFinishedTime();
            long finishTimestampMillis = TimeUnit.NANOSECONDS.toMillis(oldestFinishedTime);
            if (finishTimestampMillis <= timeSource.getCurrentTimeMillis() - FORCE_SEND_DELAY_MS) {
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

    public DelayingPendingTraceBuffer(
        int bufferSize,
        TimeSource timeSource,
        Config config,
        SharedCommunicationObjects sharedCommunicationObjects,
        HealthMetrics healthMetrics) {
      this.queue = new MpscBlockingConsumerArrayQueue<>(bufferSize);
      this.worker = newAgentThread(TRACE_MONITOR, new Worker());
      this.timeSource = timeSource;
      boolean runningSpansEnabled = config.isLongRunningTraceEnabled();
      this.runningTracesTracker =
          runningSpansEnabled
              ? new LongRunningTracesTracker(
                  config, bufferSize, sharedCommunicationObjects, healthMetrics)
              : null;
    }
  }

  static class DiscardingPendingTraceBuffer extends PendingTraceBuffer {
    private static final Logger log = LoggerFactory.getLogger(DiscardingPendingTraceBuffer.class);

    @Override
    public void start() {}

    @Override
    public void close() {}

    @Override
    public void flush() {}

    @Override
    public void enqueue(Element pendingTrace) {
      log.debug(
          "PendingTrace enqueued but won't be reported. Root span: {}", pendingTrace.getRootSpan());
    }
  }

  public static PendingTraceBuffer delaying(
      TimeSource timeSource,
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      HealthMetrics healthMetrics) {
    return new DelayingPendingTraceBuffer(
        BUFFER_SIZE, timeSource, config, sharedCommunicationObjects, healthMetrics);
  }

  public static PendingTraceBuffer discarding() {
    return new DiscardingPendingTraceBuffer();
  }

  public abstract void start();

  @Override
  public abstract void close();

  public abstract void flush();

  public abstract void enqueue(Element pendingTrace);
}
