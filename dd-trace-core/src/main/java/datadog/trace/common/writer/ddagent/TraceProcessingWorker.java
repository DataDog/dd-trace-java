package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.common.sampling.PrioritySampling.USER_DROP;
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
import org.jctools.queues.MessagePassingQueue;
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

  private final MessagePassingQueue<Object> primaryQueue;
  private final MessagePassingQueue<Object> secondaryQueue;
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
    this.primaryQueue = createQueue(capacity);
    this.secondaryQueue = createQueue(capacity);
    this.serializingHandler =
        new TraceSerializingHandler(
            primaryQueue, secondaryQueue, monitor, processor, flushInterval, timeUnit, dispatcher);
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

  public boolean publish(int samplingPriority, final List<DDSpan> data) {
    switch (samplingPriority) {
      case SAMPLER_DROP:
      case USER_DROP:
        return secondaryQueue.offer(data);
      default:
        return primaryQueue.offer(data);
    }
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

  private static MessagePassingQueue<Object> createQueue(int capacity) {
    int parallelism = Runtime.getRuntime().availableProcessors();
    return new MpscCompoundQueue<>(Math.max(capacity, parallelism), parallelism);
  }

  public static class TraceSerializingHandler
      implements Runnable, MessagePassingQueue.Consumer<Object> {

    private final MessagePassingQueue<Object> primaryQueue;
    private final MessagePassingQueue<Object> secondaryQueue;
    private final TraceProcessor processor;
    private final Monitor monitor;
    private final long ticksRequiredToFlush;
    private final boolean doTimeFlush;
    private final PayloadDispatcher payloadDispatcher;
    private long lastTicks;

    public TraceSerializingHandler(
        final MessagePassingQueue<Object> primaryQueue,
        final MessagePassingQueue<Object> secondaryQueue,
        final Monitor monitor,
        final TraceProcessor traceProcessor,
        final long flushInterval,
        final TimeUnit timeUnit,
        final PayloadDispatcher payloadDispatcher) {
      this.primaryQueue = primaryQueue;
      this.secondaryQueue = secondaryQueue;
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
    @Override
    public void accept(Object event) {
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
      while (!thread.isInterrupted()) {
        consumeFromPrimaryQueue();
      }
      log.info("datadog trace processor exited");
    }

    private void consumeFromPrimaryQueue() {
      int polls = 100;
      while (true) {
        Object event = primaryQueue.poll();
        if (null != event) {
          // there's a high priority trace, consume it,
          // and then drain whatever's in the queue now
          accept(event);
          primaryQueue.drain(this, primaryQueue.size());
          // consumed lots of high priority traces so go
          // and check if there are any low priority traces
          consumeFromSecondaryQueue();
        } else {
          if (polls > 50) {
            // is this the right approach?
            // needs measurement in a low core environment
            onSpinWait();
            --polls;
          } else if (polls > 0) {
            // this is probably better than spinning when there
            // is a low number of CPUs, perhaps should do this instead of spinning above?
            // needs measurement in a low core environment
            Thread.yield();
            --polls;
          } else { // before parking because the primary queue seems to be empty,
            // just try the secondary one in case there's some work to do
            if (!consumeFromSecondaryQueue()) {
              LockSupport.parkNanos(MILLISECONDS.toNanos(1));
              return;
            }
          }
        }
      }
    }

    private boolean consumeFromSecondaryQueue() {
      Object event = secondaryQueue.poll();
      if (null != event) {
        accept(event);
        // arbitrary limit on how much time is spent consuming from the secondary queue
        secondaryQueue.drain(this, Math.min(secondaryQueue.size(), 100));
        return true;
      }
      return false;
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
