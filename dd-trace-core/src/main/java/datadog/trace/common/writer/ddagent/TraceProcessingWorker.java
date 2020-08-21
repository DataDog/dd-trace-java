package datadog.trace.common.writer.ddagent;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.core.DDSpan;
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator;
import datadog.trace.core.monitor.Monitor;
import datadog.trace.core.processor.TraceProcessor;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

/**
 * Worker which applies rules to traces and serializes the results. Upon completion, the serialized
 * traces are published in batches to the Datadog Agent}.
 *
 * <p>publishing to the buffer will not block the calling thread, but instead will return false if
 * the buffer is full. This is to avoid impacting an application thread.
 */
@Slf4j
public class TraceProcessingWorker implements AutoCloseable {

  private final PrioritizationStrategy prioritizationStrategy;
  private final MpscBlockingConsumerArrayQueue<Object> primaryQueue;
  private final MpscBlockingConsumerArrayQueue<Object> secondaryQueue;
  private final TraceSerializingHandler serializingHandler;
  private final Thread serializerThread;
  private final int capacity;

  public TraceProcessingWorker(
      final int capacity,
      final TraceHeuristicsEvaluator traceHeuristicsEvaluator,
      final Monitor monitor,
      final PayloadDispatcher dispatcher,
      final Prioritization prioritization,
      final long flushInterval,
      final TimeUnit timeUnit) {
    this(
        capacity,
        monitor,
        dispatcher,
        new TraceProcessor(traceHeuristicsEvaluator),
        prioritization,
        flushInterval,
        timeUnit);
  }

  TraceProcessingWorker(
      final int capacity,
      final Monitor monitor,
      final PayloadDispatcher dispatcher,
      final TraceProcessor processor,
      final Prioritization prioritization,
      final long flushInterval,
      final TimeUnit timeUnit) {
    this.capacity = capacity;
    primaryQueue = createQueue(capacity);
    secondaryQueue = createQueue(capacity);
    prioritizationStrategy = prioritization.create(primaryQueue, secondaryQueue);
    serializingHandler =
        new TraceSerializingHandler(
            primaryQueue, secondaryQueue, monitor, processor, dispatcher, flushInterval, timeUnit);
    serializerThread = DaemonThreadFactory.TRACE_PROCESSOR.newThread(serializingHandler);
  }

  public void start() {
    serializerThread.start();
  }

  public boolean flush(final long timeout, final TimeUnit timeUnit) {
    final CountDownLatch latch = new CountDownLatch(1);
    final FlushEvent flush = new FlushEvent(latch);
    boolean offered;
    do {
      offered = primaryQueue.offer(flush);
    } while (!offered);
    try {
      return latch.await(timeout, timeUnit);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    serializerThread.interrupt();
  }

  public boolean publish(final int samplingPriority, final List<DDSpan> trace) {
    return prioritizationStrategy.publish(samplingPriority, trace);
  }

  public int getCapacity() {
    return capacity;
  }

  public long getRemainingCapacity() {
    // only advertise primary capacity (partly to keep test which aims to saturate the queue happy)
    return primaryQueue.remainingCapacity();
  }

  private static MpscBlockingConsumerArrayQueue<Object> createQueue(final int capacity) {
    return new MpscBlockingConsumerArrayQueue<>(capacity);
  }

  public static class TraceSerializingHandler
      implements Runnable, MessagePassingQueue.Consumer<Object> {

    private final MpscBlockingConsumerArrayQueue<Object> primaryQueue;
    private final MpscBlockingConsumerArrayQueue<Object> secondaryQueue;
    private final TraceProcessor processor;
    private final Monitor monitor;
    private final long ticksRequiredToFlush;
    private final boolean doTimeFlush;
    private final PayloadDispatcher payloadDispatcher;
    private long lastTicks;

    public TraceSerializingHandler(
        final MpscBlockingConsumerArrayQueue<Object> primaryQueue,
        final MpscBlockingConsumerArrayQueue<Object> secondaryQueue,
        final Monitor monitor,
        final TraceProcessor traceProcessor,
        final PayloadDispatcher payloadDispatcher,
        final long flushInterval,
        final TimeUnit timeUnit) {
      this.primaryQueue = primaryQueue;
      this.secondaryQueue = secondaryQueue;
      this.monitor = monitor;
      processor = traceProcessor;
      doTimeFlush = flushInterval > 0;
      this.payloadDispatcher = payloadDispatcher;
      if (doTimeFlush) {
        lastTicks = System.nanoTime();
        ticksRequiredToFlush = timeUnit.toNanos(flushInterval);
      } else {
        ticksRequiredToFlush = Long.MAX_VALUE;
      }
    }

    public void onEvent(final Object event) {
      // publish an incomplete batch if
      // 1. we get a heartbeat, and it's time to send (early heartbeats will be ignored)
      // 2. a synchronous flush command is received (at shutdown)
      try {
        if (event instanceof List) {
          final List<DDSpan> trace = (List<DDSpan>) event;
          // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
          payloadDispatcher.addTrace(processor.onTraceComplete(trace));
        } else if (event instanceof FlushEvent) {
          payloadDispatcher.flush();
          ((FlushEvent) event).sync();
        }
      } catch (final Throwable e) {
        if (log.isDebugEnabled()) {
          log.debug("Error while serializing trace", e);
        }
        final List<DDSpan> data = event instanceof List ? (List<DDSpan>) event : null;
        monitor.onFailedSerialize(data, e);
      }
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      log.info("datadog trace processor exited");
    }

    private void runDutyCycle() throws InterruptedException {
      final Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        consumeFromPrimaryQueue();
        consumeFromSecondaryQueue();
        flushIfNecessary();
      }
    }

    private void consumeFromPrimaryQueue() throws InterruptedException {
      final Object event = primaryQueue.poll(100, MILLISECONDS);
      if (null != event) {
        // there's a high priority trace, consume it,
        // and then drain whatever's in the queue
        onEvent(event);
        consumeBatch(primaryQueue);
      }
    }

    private void consumeFromSecondaryQueue() {
      // if there's something there now, take it and try to fill a batch,
      // if not, it's the secondary queue so get back to polling the primary ASAP
      final Object event = secondaryQueue.poll();
      if (null != event) {
        onEvent(event);
        consumeBatch(secondaryQueue);
      }
    }

    private void flushIfNecessary() {
      if (shouldFlush()) {
        payloadDispatcher.flush();
      }
    }

    private boolean shouldFlush() {
      if (doTimeFlush) {
        final long nanoTime = System.nanoTime();
        final long ticks = nanoTime - lastTicks;
        if (ticks > ticksRequiredToFlush) {
          lastTicks = nanoTime;
          return true;
        }
      }
      return false;
    }

    private void consumeBatch(final MessagePassingQueue<Object> queue) {
      queue.drain(this, queue.size());
    }

    @Override
    public void accept(final Object event) {
      onEvent(event);
    }
  }
}
