package datadog.trace.common.writer.ddagent;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.core.monitor.Recording;
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
      final HealthMetrics healthMetrics,
      final Monitoring monitoring,
      final PayloadDispatcher dispatcher,
      final Prioritization prioritization,
      final long flushInterval,
      final TimeUnit timeUnit) {
    this.capacity = capacity;
    this.primaryQueue = createQueue(capacity);
    this.secondaryQueue = createQueue(capacity);
    this.prioritizationStrategy = prioritization.create(primaryQueue, secondaryQueue);
    this.serializingHandler =
        new TraceSerializingHandler(
            primaryQueue,
            secondaryQueue,
            healthMetrics,
            monitoring,
            dispatcher,
            flushInterval,
            timeUnit);
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
      serializerThread.join(500);
    } catch (InterruptedException ignored) {
    }
  }

  public boolean publish(int samplingPriority, final List<DDSpan> trace) {
    return prioritizationStrategy.publish(samplingPriority, trace);
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
    private final Recording dutyCycleTimer;

    public TraceSerializingHandler(
        final MpscBlockingConsumerArrayQueue<Object> primaryQueue,
        final MpscBlockingConsumerArrayQueue<Object> secondaryQueue,
        final HealthMetrics healthMetrics,
        final Monitoring monitoring,
        final PayloadDispatcher payloadDispatcher,
        final long flushInterval,
        final TimeUnit timeUnit) {
      this.primaryQueue = primaryQueue;
      this.secondaryQueue = secondaryQueue;
      this.healthMetrics = healthMetrics;
      this.dutyCycleTimer = monitoring.newCPUTimer("tracer.duty.cycle");
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
      dutyCycleTimer.start();
      while (!thread.isInterrupted()) {
        consumeFromPrimaryQueue();
        consumeFromSecondaryQueue();
        flushIfNecessary();
        dutyCycleTimer.reset();
      }
      dutyCycleTimer.stop();
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
