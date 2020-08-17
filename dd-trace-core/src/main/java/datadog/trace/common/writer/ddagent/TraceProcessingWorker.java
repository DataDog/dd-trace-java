package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.util.ThreadUtil.onSpinWait;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.common.exec.CommonTaskExecutor;
import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.Monitor;
import datadog.trace.core.processor.TraceProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MpscCompoundQueue;

/**
 * Worker which applies rules to traces and serializes the results. Upon completion, the serialized
 * traces are published in batches to the Datadog Agent}.
 *
 * <p>publishing to the buffer will not block the calling thread, but instead will return false if
 * the buffer is full. This is to avoid impacting an application thread.
 */
@Slf4j
public class TraceProcessingWorker implements AutoCloseable {

  // empty list used to signal heartbeat, which means we could spuriously flush
  // if an empty list were published upstream, but care is taken in PendingTrace
  // and CoreTracer not to do this.
  private static final List<List<DDSpan>> HEARTBEAT = new ArrayList<>(0);

  private final MpscCompoundQueue<Object> primaryQueue;
  private final TraceSerializingHandler serializingHandler;
  private final Thread serializerThread;
  private final boolean doHeartbeat;

  private volatile ScheduledFuture<?> heartbeat;

  public TraceProcessingWorker(
      final int capacity,
      final Monitor monitor,
      final PayloadDispatcher dispatcher,
      final long flushInterval,
      final TimeUnit timeUnit,
      final boolean heartbeat) {
    this(capacity, monitor, dispatcher, new TraceProcessor(), flushInterval, timeUnit, heartbeat);
  }

  public TraceProcessingWorker(
      final int capacity,
      final Monitor monitor,
      final PayloadDispatcher dispatcher,
      final TraceProcessor processor,
      final long flushInterval,
      final TimeUnit timeUnit,
      final boolean heartbeat) {
    this.doHeartbeat = heartbeat;
    int parallelism = Runtime.getRuntime().availableProcessors();
    this.primaryQueue = new MpscCompoundQueue<>(Math.max(capacity, parallelism), parallelism);
    this.serializingHandler =
        new TraceSerializingHandler(
            primaryQueue, monitor, processor, flushInterval, timeUnit, dispatcher);
    this.serializerThread = DaemonThreadFactory.TRACE_PROCESSOR.newThread(serializingHandler);
  }

  public void start() {
    if (doHeartbeat) {
      // This provides a steady stream of events to enable flushing with a low throughput.
      heartbeat =
          CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(
              new HeartbeatTask(), this, 1000, 1000, MILLISECONDS, "disruptor heartbeat");
    }
    this.serializerThread.start();
  }

  public boolean flush(long timeout, TimeUnit timeUnit) {
    CountDownLatch latch = new CountDownLatch(1);
    FlushEvent flush = new FlushEvent(latch);
    boolean offered;
    do {
      offered = primaryQueue.offer(flush);
    } while (!offered);
    try {
      return latch.await(timeout, timeUnit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    if (null != heartbeat) {
      heartbeat.cancel(true);
    }
    serializerThread.interrupt();
  }

  public boolean publish(final List<DDSpan> data) {
    return primaryQueue.offer(data);
  }

  void heartbeat() {
    // if we don't insist on publishing a heartbeat, they might get starved out
    // if traces are very small, it might take quite a long time to fill the buffer,
    // without regular heartbeats
    boolean success;
    do {
      success = primaryQueue.offer(HEARTBEAT);
    } while (!success);
  }

  public int getCapacity() {
    return primaryQueue.capacity();
  }

  public long getRemainingCapacity() {
    return primaryQueue.capacity() - primaryQueue.size();
  }

  public static class TraceSerializingHandler implements Runnable {

    private final MpscCompoundQueue<Object> primaryQueue;
    private final TraceProcessor processor;
    private final Monitor monitor;
    private final long ticksRequiredToFlush;
    private final boolean doTimeFlush;
    private final PayloadDispatcher payloadDispatcher;
    private long lastTicks;
    private long nextFlushMillis;

    public TraceSerializingHandler(
        final MpscCompoundQueue<Object> primaryQueue,
        final Monitor monitor,
        final TraceProcessor traceProcessor,
        final long flushInterval,
        final TimeUnit timeUnit,
        final PayloadDispatcher payloadDispatcher) {
      this.primaryQueue = primaryQueue;
      this.monitor = monitor;
      this.processor = traceProcessor;
      this.doTimeFlush = flushInterval > 0;
      this.payloadDispatcher = payloadDispatcher;
      if (doTimeFlush) {
        this.lastTicks = System.nanoTime();
        this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);
      } else {
        this.ticksRequiredToFlush = Long.MAX_VALUE;
      }
    }

    @SuppressWarnings("unchecked")
    public void onEvent(Object event) {
      // publish an incomplete batch if
      // 1. we get a heartbeat, and it's time to send (early heartbeats will be ignored)
      // 2. a synchronous flush command is received (at shutdown)
      try {
        if (event instanceof List) {
          List<DDSpan> trace = (List<DDSpan>) event;
          if (trace.isEmpty()) { // a heartbeat
            if (shouldFlush()) {
              payloadDispatcher.flush();
            }
          } else {
            // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
            payloadDispatcher.addTrace(processor.onTraceComplete(trace));
          }
        } else if (event instanceof FlushEvent) {
          payloadDispatcher.flush();
          ((FlushEvent) event).sync();
        }
      } catch (final Throwable e) {
        if (log.isDebugEnabled()) {
          log.debug("Error while serializing trace", e);
        }
        List<DDSpan> data = event instanceof List ? (List<DDSpan>) event : null;
        monitor.onFailedSerialize(data, e);
      }
    }

    private boolean shouldFlush() {
      if (doTimeFlush) {
        long nanoTime = System.nanoTime();
        long ticks = nanoTime - lastTicks;
        if (ticks > ticksRequiredToFlush) {
          lastTicks = nanoTime;
          return true;
        }
      }
      return false;
    }

    @Override
    public void run() {
      Thread thread = Thread.currentThread();
      int retries = 100;
      int polls = retries;
      while (!thread.isInterrupted()) {
        Object event = primaryQueue.relaxedPoll();
        if (null != event) {
          onEvent(event);
          polls = retries;
        } else {
          if (polls > 50) {
            onSpinWait();
            --polls;
          } else if (polls > 0) {
            Thread.yield();
            --polls;
          } else {
            LockSupport.parkNanos(MILLISECONDS.toNanos(1));
          }
        }
      }
      log.info("datadog trace processor exited");
    }
  }

  // Important to use explicit class to avoid implicit hard references to TraceProcessingWorker
  private static final class HeartbeatTask
      implements CommonTaskExecutor.Task<TraceProcessingWorker> {
    @Override
    public void run(final TraceProcessingWorker traceProcessor) {
      traceProcessor.heartbeat();
    }
  }

  private static final class FlushEvent {
    private final CountDownLatch latch;

    private FlushEvent(CountDownLatch latch) {
      this.latch = latch;
    }

    void sync() {
      latch.countDown();
    }
  }
}
