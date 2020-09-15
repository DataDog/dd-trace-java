package datadog.trace.core;

import static datadog.common.exec.DaemonThreadFactory.TRACE_MONITOR;

import datadog.trace.core.util.Clock;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;

class PendingTraceBuffer implements AutoCloseable {
  private final long FORCE_SEND_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
  private final long SEND_DELAY_NS = TimeUnit.MILLISECONDS.toNanos(5);
  private final long SLEEP_TIME_MS = 1;

  private final ConcurrentLinkedQueue<PendingTrace> queue = new ConcurrentLinkedQueue<>();
  private final AtomicReference<Thread> thread = new AtomicReference<>();

  public void enqueue(PendingTrace pendingTrace) {
    queue.add(pendingTrace);
  }

  public void start() {
    if (thread.get() == null) {
      Thread newThread = TRACE_MONITOR.newThread(new Worker());
      if (thread.compareAndSet(null, newThread)) {
        newThread.start();
      }
    }
  }

  @Override
  public void close() {
    Thread toClose = thread.getAndSet(null);
    if (toClose != null) {
      toClose.interrupt();
      try {
        toClose.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void flush() {
    PendingTrace pendingTrace = queue.poll();
    while (pendingTrace != null) {
      pendingTrace.write();
      pendingTrace = queue.poll();
    }
  }

  private final class Worker implements Runnable {

    @SneakyThrows
    @Override
    public void run() {
      while (!Thread.interrupted()) {
        PendingTrace pendingTrace = queue.poll();

        if (pendingTrace == null) {
          // Queue is empty.  Lets sleep and try again.
          sleep();
          continue;
        }

        if (pendingTrace.isEmpty()) {
          // "write" it out to allow cleanup.
          pendingTrace.write();
          continue;
        }

        DDSpan rootSpan = pendingTrace.getRootSpan();

        long finishTimestampMillis =
            TimeUnit.NANOSECONDS.toMillis(rootSpan.getStartTime() + rootSpan.getDurationNano());
        if (finishTimestampMillis + FORCE_SEND_DELAY_MS <= System.currentTimeMillis()) {
          // Root span is getting old. We need to send the trace to avoid being discarded by agent.
          pendingTrace.write();
          continue;
        }

        long currentNanoTicks = Clock.currentNanoTicks();
        long lastReferenced = pendingTrace.getLastReferenced();
        long delta = currentNanoTicks - lastReferenced;

        if (SEND_DELAY_NS <= delta) {
          // Trace has been unmodified long enough, go ahead and write whatever is finished.
          pendingTrace.write();
        } else {
          // Trace is too new.  Requeue it and sleep to avoid a hot loop.
          queue.add(pendingTrace);
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
