package datadog.trace.common.writer;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.communication.ddagent.DroppingPolicy;
import datadog.trace.common.writer.ddagent.FlushEvent;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker which applies rules to traces and serializes the results. Upon completion, the serialized
 * traces are published in batches to the Datadog Agent}.
 *
 * <p>publishing to the buffer will not block the calling thread, but instead will return false if
 * the buffer is full. This is to avoid impacting an application thread.
 */
public class TraceProcessingWorker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TraceProcessingWorker.class);

  private final PrioritizationStrategy prioritizationStrategy;
  private final MpscBlockingConsumerArrayQueue<Object> primaryQueue;
  private final MpscBlockingConsumerArrayQueue<Object> secondaryQueue;
  private final TraceSerializingHandler serializingHandler;
  private final Thread serializerThread;
  private final int capacity;

  public TraceProcessingWorker(
      final int capacity,
      final HealthMetrics healthMetrics,
      final PayloadDispatcher dispatcher,
      final DroppingPolicy droppingPolicy,
      final Prioritization prioritization,
      final long flushInterval,
      final TimeUnit timeUnit) {
    this.capacity = capacity;
    this.primaryQueue = createQueue(capacity);
    this.secondaryQueue = createQueue(capacity);
    this.prioritizationStrategy =
        prioritization.create(primaryQueue, secondaryQueue, droppingPolicy);
    this.serializingHandler =
        new TraceSerializingHandler(
            primaryQueue, secondaryQueue, healthMetrics, dispatcher, flushInterval, timeUnit);
    this.serializerThread = newAgentThread(TRACE_PROCESSOR, serializingHandler);
  }

  public void start() {
    this.serializerThread.start();
  }

  public boolean flush(long timeout, TimeUnit timeUnit) {
    CountDownLatch latch = new CountDownLatch(1);
    FlushEvent flush = new FlushEvent(latch);
    boolean offered;
    do {
      offered = primaryQueue.offer(flush);
    } while (!offered && serializerThread.isAlive());
    try {
      return latch.await(timeout, timeUnit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    serializerThread.interrupt();
    try {
      serializerThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  public <T extends CoreSpan<T>> boolean publish(
      T root, int samplingPriority, final List<T> trace) {
    return prioritizationStrategy.publish(root, samplingPriority, trace);
  }

  public int getCapacity() {
    return capacity;
  }

  public long getRemainingCapacity() {
    // only advertise primary capacity (partly to keep test which aims to saturate the queue happy)
    return primaryQueue.remainingCapacity();
  }

  private static MpscBlockingConsumerArrayQueue<Object> createQueue(int capacity) {
    return new MpscBlockingConsumerArrayQueue<>(capacity);
  }

  public static class TraceSerializingHandler
      implements Runnable, MessagePassingQueue.Consumer<Object> {

    private final MpscBlockingConsumerArrayQueue<Object> primaryQueue;
    private final MpscBlockingConsumerArrayQueue<Object> secondaryQueue;
    private final HealthMetrics healthMetrics;
    private final long ticksRequiredToFlush;
    private final boolean doTimeFlush;
    private final PayloadDispatcher payloadDispatcher;
    private long lastTicks;

    public TraceSerializingHandler(
        final MpscBlockingConsumerArrayQueue<Object> primaryQueue,
        final MpscBlockingConsumerArrayQueue<Object> secondaryQueue,
        final HealthMetrics healthMetrics,
        final PayloadDispatcher payloadDispatcher,
        final long flushInterval,
        final TimeUnit timeUnit) {
      this.primaryQueue = primaryQueue;
      this.secondaryQueue = secondaryQueue;
      this.healthMetrics = healthMetrics;
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
          // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
          payloadDispatcher.addTrace(trace);
        } else if (event instanceof FlushEvent) {
          payloadDispatcher.flush();
          ((FlushEvent) event).sync();
        }
      } catch (final Throwable e) {
        if (log.isDebugEnabled()) {
          log.debug("Error while serializing trace", e);
        }
        List<DDSpan> data = event instanceof List ? (List<DDSpan>) event : null;
        healthMetrics.onFailedSerialize(data, e);
      }
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      log.debug("Datadog trace processor exited. Publishing traces stopped");
    }

    private void runDutyCycle() throws InterruptedException {
      Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        consumeFromPrimaryQueue();
        consumeFromSecondaryQueue();
        flushIfNecessary();
      }
    }

    private void consumeFromPrimaryQueue() throws InterruptedException {
      Object event = primaryQueue.poll(100, MILLISECONDS);
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
      Object event = secondaryQueue.poll();
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
        long nanoTime = System.nanoTime();
        long ticks = nanoTime - lastTicks;
        if (ticks > ticksRequiredToFlush) {
          lastTicks = nanoTime;
          return true;
        }
      }
      return false;
    }

    private void consumeBatch(MessagePassingQueue<Object> queue) {
      queue.drain(this, queue.size());
    }

    @Override
    public void accept(Object event) {
      onEvent(event);
    }
  }
}
