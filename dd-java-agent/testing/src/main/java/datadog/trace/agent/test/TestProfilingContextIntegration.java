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
import java.util.concurrent.ConcurrentMap;
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
  private final ConcurrentMap<Thread, AtomicInteger> parkEnterCallsByThread =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Thread, AtomicInteger> acceptedParkEnterCallsByThread =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Thread, AtomicInteger> parkExitCallsByThread =
      new ConcurrentHashMap<>();
  private final Set<Thread> activeParkEntries = ConcurrentHashMap.newKeySet();
  private final AtomicLong lastParkBlocker = new AtomicLong();
  private final AtomicLong lastUnblockingSpanId = new AtomicLong();
  private final ConcurrentMap<Thread, AtomicLong> lastUnblockingSpanIdByThread =
      new ConcurrentHashMap<>();
  private final Set<Thread> parkExitThreads = ConcurrentHashMap.newKeySet();
  private final BlockingDeque<Timing> closedTimings = new LinkedBlockingDeque<>();
  private final Logger logger = LoggerFactory.getLogger(TestProfilingContextIntegration.class);
  private volatile boolean acceptParkEntries = true;
  private volatile boolean unparkAttributionEnabled = true;
  private final AtomicInteger taskBlockBeginCalls = new AtomicInteger();
  private final AtomicInteger taskBlockEndCalls = new AtomicInteger();
  private final ConcurrentMap<Thread, AtomicInteger> taskBlockBeginCallsByThread =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Thread, AtomicInteger> taskBlockEndCallsByThread =
      new ConcurrentHashMap<>();
  private final AtomicLong nextTaskBlockToken = new AtomicLong();
  private final AtomicLong lastTaskBlockBlocker = new AtomicLong();
  private final AtomicLong lastTaskBlockUnblockingSpanId = new AtomicLong();
  private volatile boolean acceptTaskBlockEntries = true;

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
    parkEnterCallsByThread.clear();
    acceptedParkEnterCallsByThread.clear();
    parkExitCallsByThread.clear();
    activeParkEntries.clear();
    lastParkBlocker.set(0);
    lastUnblockingSpanId.set(0);
    lastUnblockingSpanIdByThread.clear();
    parkExitThreads.clear();
    acceptParkEntries = true;
    unparkAttributionEnabled = true;
    taskBlockBeginCalls.set(0);
    taskBlockEndCalls.set(0);
    taskBlockBeginCallsByThread.clear();
    taskBlockEndCallsByThread.clear();
    nextTaskBlockToken.set(0);
    lastTaskBlockBlocker.set(0);
    lastTaskBlockUnblockingSpanId.set(0);
    acceptTaskBlockEntries = true;
  }

  @Override
  public boolean parkEnter() {
    parkEnterCalls.incrementAndGet();
    parkEnterCallsByThread
        .computeIfAbsent(Thread.currentThread(), ignored -> new AtomicInteger())
        .incrementAndGet();
    boolean accepted = acceptParkEntries && activeParkEntries.add(Thread.currentThread());
    if (accepted) {
      acceptedParkEnterCallsByThread
          .computeIfAbsent(Thread.currentThread(), ignored -> new AtomicInteger())
          .incrementAndGet();
    }
    return accepted;
  }

  @Override
  public void parkExit(long blocker, long unblockingSpanId) {
    activeParkEntries.remove(Thread.currentThread());
    parkExitCalls.incrementAndGet();
    parkExitCallsByThread
        .computeIfAbsent(Thread.currentThread(), ignored -> new AtomicInteger())
        .incrementAndGet();
    lastParkBlocker.set(blocker);
    lastUnblockingSpanId.set(unblockingSpanId);
    if (unblockingSpanId != 0) {
      // Keyed by thread so assertions are immune to unrelated parks on background threads.
      lastUnblockingSpanIdByThread
          .computeIfAbsent(Thread.currentThread(), ignored -> new AtomicLong())
          .set(unblockingSpanId);
    }
    parkExitThreads.add(Thread.currentThread());
  }

  @Override
  public boolean isUnparkAttributionEnabled() {
    return unparkAttributionEnabled;
  }

  @Override
  public long beginTaskBlock() {
    taskBlockBeginCalls.incrementAndGet();
    taskBlockBeginCallsByThread
        .computeIfAbsent(Thread.currentThread(), ignored -> new AtomicInteger())
        .incrementAndGet();
    return acceptTaskBlockEntries ? nextTaskBlockToken.incrementAndGet() : 0L;
  }

  @Override
  public boolean endTaskBlock(long token, long blocker, long unblockingSpanId) {
    if (token == 0L) {
      return false;
    }
    taskBlockEndCalls.incrementAndGet();
    taskBlockEndCallsByThread
        .computeIfAbsent(Thread.currentThread(), ignored -> new AtomicInteger())
        .incrementAndGet();
    lastTaskBlockBlocker.set(blocker);
    lastTaskBlockUnblockingSpanId.set(unblockingSpanId);
    return true;
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

  public int getParkEnterCalls(Thread thread) {
    AtomicInteger calls = parkEnterCallsByThread.get(thread);
    return calls == null ? 0 : calls.get();
  }

  public int getParkExitCalls(Thread thread) {
    AtomicInteger calls = parkExitCallsByThread.get(thread);
    return calls == null ? 0 : calls.get();
  }

  public int getAcceptedParkEnterCalls(Thread thread) {
    AtomicInteger calls = acceptedParkEnterCallsByThread.get(thread);
    return calls == null ? 0 : calls.get();
  }

  public AtomicLong getLastParkBlocker() {
    return lastParkBlocker;
  }

  public AtomicLong getLastUnblockingSpanId() {
    return lastUnblockingSpanId;
  }

  /** Returns the last non-zero unblocking span id drained by a park exit on {@code thread}. */
  public long getLastUnblockingSpanId(Thread thread) {
    AtomicLong spanId = lastUnblockingSpanIdByThread.get(thread);
    return spanId == null ? 0L : spanId.get();
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

  public void setAcceptParkEntries(boolean acceptParkEntries) {
    this.acceptParkEntries = acceptParkEntries;
  }

  public void setUnparkAttributionEnabled(boolean unparkAttributionEnabled) {
    this.unparkAttributionEnabled = unparkAttributionEnabled;
  }

  public AtomicInteger getTaskBlockBeginCalls() {
    return taskBlockBeginCalls;
  }

  public AtomicInteger getTaskBlockEndCalls() {
    return taskBlockEndCalls;
  }

  public int getTaskBlockBeginCalls(Thread thread) {
    AtomicInteger calls = taskBlockBeginCallsByThread.get(thread);
    return calls == null ? 0 : calls.get();
  }

  public int getTaskBlockEndCalls(Thread thread) {
    AtomicInteger calls = taskBlockEndCallsByThread.get(thread);
    return calls == null ? 0 : calls.get();
  }

  public AtomicLong getLastTaskBlockBlocker() {
    return lastTaskBlockBlocker;
  }

  public AtomicLong getLastTaskBlockUnblockingSpanId() {
    return lastTaskBlockUnblockingSpanId;
  }

  public void setAcceptTaskBlockEntries(boolean acceptTaskBlockEntries) {
    this.acceptTaskBlockEntries = acceptTaskBlockEntries;
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
