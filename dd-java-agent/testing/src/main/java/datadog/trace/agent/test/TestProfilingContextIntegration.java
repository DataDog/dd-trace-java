// Copyright 2026 Datadog, Inc.
package datadog.trace.agent.test;

import datadog.trace.api.EndpointTracker;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestProfilingContextIntegration implements ProfilingContextIntegration {
  private final AtomicInteger attachments = new AtomicInteger();
  private final AtomicInteger detachments = new AtomicInteger();
  private final AtomicInteger counter = new AtomicInteger();
  private final AtomicInteger parkEnterCalls = new AtomicInteger();
  private final AtomicInteger parkExitCalls = new AtomicInteger();
  private final AtomicLong lastParkBlocker = new AtomicLong();
  private final AtomicLong lastUnblockingSpanId = new AtomicLong();
  private final Set<Thread> parkExitThreads = ConcurrentHashMap.newKeySet();
  private final BlockingDeque<Timing> closedTimings = new LinkedBlockingDeque<>();
  private final Logger logger = LoggerFactory.getLogger(TestProfilingContextIntegration.class);
  private volatile boolean acceptParkEntries = true;

  @Override
  public void onAttach() {
    attachments.incrementAndGet();
  }

  @Override
  public void onDetach() {
    detachments.incrementAndGet();
  }

  public void clear() {
    attachments.set(0);
    detachments.set(0);
    parkEnterCalls.set(0);
    parkExitCalls.set(0);
    lastParkBlocker.set(0);
    lastUnblockingSpanId.set(0);
    parkExitThreads.clear();
    acceptParkEntries = true;
  }

  @Override
  public boolean parkEnter() {
    parkEnterCalls.incrementAndGet();
    return acceptParkEntries;
  }

  @Override
  public void parkExit(long blocker, long unblockingSpanId) {
    parkExitCalls.incrementAndGet();
    lastParkBlocker.set(blocker);
    lastUnblockingSpanId.set(unblockingSpanId);
    parkExitThreads.add(Thread.currentThread());
  }

  @Override
  public String name() {
    return "test";
  }

  @Override
  public ProfilingContextAttribute createContextAttribute(String attribute) {
    return ProfilingContextAttribute.NoOp.INSTANCE;
  }

  @Override
  public ProfilingScope newScope() {
    return ProfilingScope.NO_OP;
  }

  @Override
  public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

  @Override
  public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
    return EndpointTracker.NO_OP;
  }

  @Override
  public Timing start(TimerType type) {
    if (type == TimerType.QUEUEING) {
      return new TestQueueTiming();
    }
    return Timing.NoOp.INSTANCE;
  }

  public AtomicInteger getAttachments() {
    return attachments;
  }

  public AtomicInteger getDetachments() {
    return detachments;
  }

  public AtomicInteger getCounter() {
    return counter;
  }

  public AtomicInteger getParkEnterCalls() {
    return parkEnterCalls;
  }

  public AtomicInteger getParkExitCalls() {
    return parkExitCalls;
  }

  public AtomicLong getLastParkBlocker() {
    return lastParkBlocker;
  }

  public AtomicLong getLastUnblockingSpanId() {
    return lastUnblockingSpanId;
  }

  public Set<Thread> getParkExitThreads() {
    return parkExitThreads;
  }

  public BlockingDeque<Timing> getClosedTimings() {
    return closedTimings;
  }

  public Logger getLogger() {
    return logger;
  }

  public boolean getAcceptParkEntries() {
    return acceptParkEntries;
  }

  public boolean isAcceptParkEntries() {
    return acceptParkEntries;
  }

  public void setAcceptParkEntries(boolean acceptParkEntries) {
    this.acceptParkEntries = acceptParkEntries;
  }

  public boolean isBalanced() {
    return counter.get() == 0;
  }

  public class TestQueueTiming implements QueueTiming {

    private Class<?> task;
    private Class<?> scheduler;
    private Class<?> queue;
    private int queueLength;
    private final Thread origin;
    private final long start;

    public TestQueueTiming() {
      counter.incrementAndGet();
      origin = Thread.currentThread();
      start = System.currentTimeMillis();
    }

    @Override
    public void setTask(Object task) {
      this.task = TaskWrapper.getUnwrappedType(task);
    }

    @Override
    public void setScheduler(Class<?> scheduler) {
      this.scheduler = scheduler;
    }

    @Override
    public void setQueue(Class<?> queue) {
      this.queue = queue;
    }

    @Override
    public void setQueueLength(int queueLength) {
      this.queueLength = queueLength;
    }

    @Override
    public void report() {
      counter.decrementAndGet();
      AgentSpan span = AgentTracer.activeSpan();
      long activeSpanId = span == null ? 0 : span.getSpanId();
      long duration = System.currentTimeMillis() - start;
      logger.debug(
          "task {} with spanId={} migrated from {} to {} in {}ms, scheduled by {}",
          task.getSimpleName(),
          activeSpanId,
          origin.getName(),
          Thread.currentThread().getName(),
          duration,
          scheduler.getName());
      closedTimings.offer(this);
    }

    @Override
    public boolean sample() {
      return true;
    }

    public Class<?> getTask() {
      return task;
    }

    public Class<?> getScheduler() {
      return scheduler;
    }

    public Class<?> getQueue() {
      return queue;
    }

    public int getQueueLength() {
      return queueLength;
    }

    public Thread getOrigin() {
      return origin;
    }

    public long getStart() {
      return start;
    }

    @Override
    public String toString() {
      return task.getName();
    }
  }
}
