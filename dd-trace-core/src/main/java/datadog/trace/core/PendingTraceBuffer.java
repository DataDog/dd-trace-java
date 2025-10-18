package datadog.trace.core;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_MONITOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.Comparator.comparingLong;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.time.TimeSource;
import datadog.trace.common.writer.TraceDumpJsonExporter;
import datadog.trace.core.monitor.HealthMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.zip.ZipOutputStream;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class PendingTraceBuffer implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(PendingTraceBuffer.class);
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
    private static final CommandElement FLUSH_ELEMENT = new CommandElement();
    private static final CommandElement DUMP_ELEMENT = new CommandElement();
    private static final CommandElement STAND_IN_ELEMENT = new CommandElement();

    private final MpscBlockingConsumerArrayQueue<Element> queue;
    private final Thread worker;
    private final TimeSource timeSource;

    private volatile boolean closed = false;
    private final AtomicInteger flushCounter = new AtomicInteger(0);
    private final AtomicInteger dumpCounter = new AtomicInteger(0);

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
      TracerFlare.addReporter(new TracerDump(this));
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
        boolean signaled = queue.offer(FLUSH_ELEMENT);
        while (!closed && !signaled) {
          yieldOrSleep(loop++);
          signaled = queue.offer(FLUSH_ELEMENT);
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

    private static final class DumpDrain
        implements MessagePassingQueue.Consumer<Element>, MessagePassingQueue.Supplier<Element> {
      private static final DumpDrain DUMP_DRAIN = new DumpDrain();
      private static final int MAX_DUMPED_TRACES = 50;

      private static final Comparator<Element> TRACE_BY_START_TIME =
          comparingLong(trace -> trace.getRootSpan().getStartTime());
      private static final Predicate<Element> NOT_PENDING_TRACE =
          element -> !(element instanceof PendingTrace);

      private volatile List<Element> data = new ArrayList<>();
      private volatile int index = 0;

      @Override
      public void accept(Element pendingTrace) {
        data.add(pendingTrace);
      }

      @Override
      public Element get() {
        if (index < data.size()) {
          return data.get(index++);
        }
        // Should never reach here or else queue may break according to
        // MessagePassingQueue docs if we return a null. Return a stand-in
        // Element instead.
        LOGGER.warn(
            "Index {} is out of bounds for data size {} in DumpDrain.get so returning filler CommandElement to prevent pending trace queue from breaking.",
            index,
            data.size());
        return STAND_IN_ELEMENT;
      }

      public List<Element> collectTraces() {
        List<Element> traces = data;
        data = new ArrayList<>();
        index = 0;
        traces.removeIf(NOT_PENDING_TRACE);
        // Storing oldest traces first
        traces.sort(TRACE_BY_START_TIME);
        return traces;
      }
    }

    private static final class CommandElement implements Element {
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

            if (pendingTrace == FLUSH_ELEMENT) {
              // Since this is an MPSC queue, the drain needs to be called on the consumer thread
              queue.drain(WriteDrain.WRITE_DRAIN);
              flushCounter.incrementAndGet();
              continue;
            }

            if (pendingTrace == DUMP_ELEMENT) {
              queue.fill(
                  DumpDrain.DUMP_DRAIN,
                  queue.drain(DumpDrain.DUMP_DRAIN, DumpDrain.MAX_DUMPED_TRACES));
              dumpCounter.incrementAndGet();
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
              // Trace is too new. Requeue it and sleep to avoid a hot loop.
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

  private static class TracerDump implements TracerFlare.Reporter {
    private final DelayingPendingTraceBuffer buffer;

    private TracerDump(DelayingPendingTraceBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public void prepareForFlare() {
      if (buffer.worker.isAlive()) {
        int count = buffer.dumpCounter.get();
        int loop = 1;
        boolean signaled = buffer.queue.offer(DelayingPendingTraceBuffer.DUMP_ELEMENT);
        while (!buffer.closed && !signaled) {
          buffer.yieldOrSleep(loop++);
          signaled = buffer.queue.offer(DelayingPendingTraceBuffer.DUMP_ELEMENT);
        }
        int newCount = buffer.dumpCounter.get();
        while (!buffer.closed && count >= newCount) {
          buffer.yieldOrSleep(loop++);
          newCount = buffer.dumpCounter.get();
        }
      }
    }

    @Override
    public void addReportToFlare(ZipOutputStream zip) throws IOException {
      TraceDumpJsonExporter writer = new TraceDumpJsonExporter(zip);
      for (Element e : DelayingPendingTraceBuffer.DumpDrain.DUMP_DRAIN.collectTraces()) {
        if (e instanceof PendingTrace) {
          PendingTrace trace = (PendingTrace) e;
          writer.write(trace.getSpans());
        }
      }
      writer.flush();
    }
  }
}
