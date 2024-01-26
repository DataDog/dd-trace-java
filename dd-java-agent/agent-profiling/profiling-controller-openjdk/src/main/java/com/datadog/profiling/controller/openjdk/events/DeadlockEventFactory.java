package com.datadog.profiling.controller.openjdk.events;

import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Event;

public class DeadlockEventFactory {
  private static final AtomicBoolean EVENTS_REGISTERED_FLAG = new AtomicBoolean();

  private static final DeadlockEvent DEADLOCK_EVENT = new DeadlockEvent();
  private static final DeadlockedThreadEvent DEADLOCKED_THREAD_EVENT = new DeadlockedThreadEvent();

  private final ThreadMXBean threadMXBean;
  private final AtomicLong deadlockCounter = new AtomicLong();

  public static void registerEvents() {
    // prevent re-registration as that would cause JFR to throw an exception
    if (EVENTS_REGISTERED_FLAG.compareAndSet(false, true)) {
      JfrHelper.addPeriodicEvent(DeadlockEvent.class, DeadlockEvent::emit);
    }
  }

  DeadlockEventFactory(ThreadMXBean threadMXBean) {
    this.threadMXBean = threadMXBean;
  }

  DeadlockEventFactory() {
    this(ManagementFactory.getThreadMXBean());
  }

  final List<? extends Event> collectEvents() {
    if (!isDeadlockEventEnabled()) {
      return Collections.emptyList();
    }

    long[] locked = threadMXBean.findDeadlockedThreads();
    if (locked == null) {
      return Collections.emptyList();
    }
    long id = deadlockCounter.getAndIncrement();

    List<Event> events = new ArrayList<>();
    DeadlockEvent event = new DeadlockEvent(id, locked.length);

    events.add(event);
    if (isDeadlockedThreadEventEnabled()) {
      ThreadInfo[] lockedThreads = threadMXBean.getThreadInfo(locked, true, true);

      Map<LockInfo, Set<StackTraceElement>> waitingFrames =
          new TreeMap<>(Comparator.comparingLong(LockInfo::getIdentityHashCode));
      for (ThreadInfo ti : lockedThreads) {
        waitingFrames
            .computeIfAbsent(ti.getLockInfo(), k -> new HashSet<>())
            .add(ti.getStackTrace()[0]);
      }

      for (ThreadInfo ti : lockedThreads) {
        processLockedMonitors(ti, id, waitingFrames, events);
        processSynchronizables(ti, id, waitingFrames, events);
      }
    }
    return events;
  }

  private void processSynchronizables(
      ThreadInfo ti,
      long id,
      Map<LockInfo, Set<StackTraceElement>> waitingFrames,
      List<Event> events) {
    for (LockInfo li : ti.getLockedSynchronizers()) {
      Set<StackTraceElement> waitingFramesSet = waitingFrames.get(li);
      if (waitingFramesSet != null) {
        for (StackTraceElement waitingFrame : waitingFramesSet) {
          events.add(
              new DeadlockedThreadEvent(
                  id,
                  ti.getThreadId(),
                  ti.getThreadName(),
                  ti.getLockOwnerId(),
                  ti.getLockOwnerName(),
                  ti.getLockName(),
                  null,
                  frameAsString(waitingFrame)));
        }
      }
    }
  }

  private void processLockedMonitors(
      ThreadInfo ti,
      long id,
      Map<LockInfo, Set<StackTraceElement>> waitingFrames,
      List<Event> events) {
    for (MonitorInfo mi : ti.getLockedMonitors()) {
      Set<StackTraceElement> waitingFramesSet = waitingFrames.get(mi);
      if (waitingFramesSet != null) {
        for (StackTraceElement waitingFrame : waitingFramesSet) {
          events.add(
              new DeadlockedThreadEvent(
                  id,
                  ti.getThreadId(),
                  ti.getThreadName(),
                  ti.getLockOwnerId(),
                  ti.getLockOwnerName(),
                  ti.getLockName(),
                  frameAsString(mi.getLockedStackFrame()),
                  frameAsString(waitingFrame)));
        }
      }
    }
  }

  boolean isDeadlockEventEnabled() {
    return DEADLOCK_EVENT.isEnabled() && DEADLOCK_EVENT.shouldCommit();
  }

  boolean isDeadlockedThreadEventEnabled() {
    return DEADLOCKED_THREAD_EVENT.isEnabled() && DEADLOCKED_THREAD_EVENT.shouldCommit();
  }

  private static String frameAsString(StackTraceElement ste) {
    return ste.getClassName() + "." + ste.getMethodName() + "#" + ste.getLineNumber();
  }
}
