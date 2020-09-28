package datadog.trace.core;

import static datadog.common.exec.DaemonThreadFactory.TRACE_MONITOR;

import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

class PendingTraceBuffer implements AutoCloseable {
  /** to correspond with DDAgentWriter.BUFFER_SIZE */
  private static final int BUFFER_SIZE = 1024;

  private final long FORCE_SEND_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
  private final long SEND_DELAY_NS = TimeUnit.MILLISECONDS.toNanos(5);
  private final long SLEEP_TIME_MS = 1;

  private final MpscBlockingConsumerArrayQueue<PendingTrace> queue =
      new MpscBlockingConsumerArrayQueue<>(BUFFER_SIZE);
  private final Thread worker = TRACE_MONITOR.newThread(new Worker());

  private volatile boolean closed = false;

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
  }

  public void flush() {
    queue.drain(
        new MessagePassingQueue.Consumer<PendingTrace>() {
          @Override
          public void accept(PendingTrace pendingTrace) {
            pendingTrace.write();
          }
        });
  }

  private final class Worker implements Runnable {

    @SneakyThrows
    @Override
    public void run() {
      while (!closed && !Thread.currentThread().isInterrupted()) {

        PendingTrace pendingTrace = queue.take(); // block until available.

        DDSpan rootSpan = pendingTrace.getRootSpan();

        long finishTimestampMillis =
            TimeUnit.NANOSECONDS.toMillis(rootSpan.getStartTime() + rootSpan.getDurationNano());
        if (finishTimestampMillis + FORCE_SEND_DELAY_MS <= System.currentTimeMillis()) {
          // Root span is getting old. We need to send the trace to avoid being discarded by agent.
          pendingTrace.write();
          continue;
        }

        if (pendingTrace.lastReferencedNanosAgo(SEND_DELAY_NS)) {
          // Trace has been unmodified long enough, go ahead and write whatever is finished.
          pendingTrace.write();
        } else {
          // Trace is too new.  Requeue it and sleep to avoid a hot loop.
          enqueue(pendingTrace);
          sleep();
        }
      }
    }

    private void sleep() {
      try {
        Thread.sleep(SLEEP_TIME_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
