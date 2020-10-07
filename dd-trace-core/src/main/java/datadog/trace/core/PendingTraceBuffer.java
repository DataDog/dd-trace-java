package datadog.trace.core;

import static datadog.common.exec.DaemonThreadFactory.TRACE_MONITOR;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

@Slf4j
class PendingTraceBuffer implements AutoCloseable {
  private static final int BUFFER_SIZE = 1 << 12; // 4096

  private final long FORCE_SEND_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
  private final long SEND_DELAY_NS = TimeUnit.MILLISECONDS.toNanos(500);
  private final long SLEEP_TIME_MS = 100;

  private final MpscBlockingConsumerArrayQueue<PendingTrace> queue =
      new MpscBlockingConsumerArrayQueue<>(BUFFER_SIZE);
  private final Thread worker = TRACE_MONITOR.newThread(new Worker());

  private volatile boolean closed = false;

  /** if the queue is full, pendingTrace trace will be written immediately. */
  public void enqueue(PendingTrace pendingTrace) {
    if (queue.offer(pendingTrace)) {
      log.debug("t_id={} pending trace enqueued");
    } else {
      // Queue is full, so we can't buffer this trace, write it out directly instead.
      log.debug("t_id={} buffer full -- pending trace written");
      pendingTrace.write();
    }
  }

  public void start() {
    log.debug("Starting {}", this);
    worker.start();
  }

  public void assertRunning() {
    assert worker.isAlive();
    assert !closed;
  }

  @Override
  public void close() {
    log.debug("Stopping {}", this);
    closed = true;
    worker.interrupt();
  }

  public void flush() {
    log.debug("Flushing {}", this);
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
}
