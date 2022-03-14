package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilerTracingContextTrackerFactory
    implements TracingContextTrackerFactory.Implementation {
  private static final Logger log =
      LoggerFactory.getLogger(ProfilerTracingContextTrackerFactory.class);

  private static final long DEFAULT_INACTIVITY_CHECK_PERIOD_MS = 5_000L; // 5 seconds

  private final DelayQueue<TracingContextTracker.DelayedTracker> delayQueue = new DelayQueue<>();

  public static void register(ConfigProvider configProvider) {
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_TRACING_CONTEXT_ENABLED,
        ProfilingConfig.PROFILING_TRACING_CONTEXT_ENABLED_DEFAULT)) {
      long inactivityDelayNs =
          TimeUnit.NANOSECONDS.convert(
              configProvider.getInteger(
                  ProfilingConfig.PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_SEC,
                  ProfilingConfig.PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_DEFAULT),
              TimeUnit.SECONDS);
      int reservedMemorySize =
          configProvider.getInteger(
              ProfilingConfig.PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_SIZE,
              ProfilingConfig.PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_SIZE_DEFAULT);
      String reservedMemoryType =
          configProvider.getString(
              ProfilingConfig.PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_TYPE,
              ProfilingConfig.PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_TYPE_DEFAULT);

      TracingContextTrackerFactory.registerImplementation(
          new ProfilerTracingContextTrackerFactory(
              inactivityDelayNs,
              DEFAULT_INACTIVITY_CHECK_PERIOD_MS,
              reservedMemorySize,
              reservedMemoryType));
    }
  }

  private void initializeInactiveTrackerCleanup(long inactivityCheckPeriodMs) {
    AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
        target -> {
          Collection<TracingContextTracker.DelayedTracker> timeouts = new ArrayList<>(500);
          int drained = 0;
          do {
            drained = target.drainTo(timeouts);
            if (drained > 0) {
              log.debug("Drained {} inactive trackers", drained);
            }
            Iterator<TracingContextTracker.DelayedTracker> iterator = timeouts.iterator();
            while (iterator.hasNext()) {
              iterator.next().cleanup();
              iterator.remove();
            }
          } while (drained > 0);
        },
        delayQueue,
        inactivityCheckPeriodMs,
        inactivityCheckPeriodMs,
        TimeUnit.MILLISECONDS);
  }

  private final Allocator allocator;
  private final Set<TracingContextTracker.IntervalBlobListener> blobListeners = new HashSet<>();
  private final IntervalSequencePruner sequencePruner = new IntervalSequencePruner();
  private final ProfilerTracingContextTracker.TimestampProvider timestampProvider;
  private final long inactivityDelay;

  ProfilerTracingContextTrackerFactory(
      long inactivityDelayNs, long inactivityCheckPeriodMs, int reservedMemorySize) {
    this(inactivityDelayNs, inactivityCheckPeriodMs, reservedMemorySize, "heap");
  }

  ProfilerTracingContextTrackerFactory(
      long inactivityDelayNs,
      long inactivityCheckPeriodMs,
      int reservedMemorySize,
      String reservedMemoryType) {
    ProfilerTracingContextTracker.TimestampProvider tsProvider = System::nanoTime;
    try {
      Class<?> clz =
          ProfilerTracingContextTracker.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
      MethodHandle mh =
          MethodHandles.lookup().findStatic(clz, "counterTime", MethodType.methodType(long.class));
      tsProvider =
          () -> {
            try {
              return (long) mh.invokeExact();
            } catch (OutOfMemoryError e) {
              throw e;
            } catch (Throwable ignored) {
              return -1L;
            }
          };
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.warn("Failed to initialize JFR timestamp access. Falling back to system nanotime.", t);
      } else {
        log.debug("Failed to initialize JFR timestamp access. Falling back to system nanotime.");
      }
    }
    log.info("Tracing Context Tracker Memory Type: {}", reservedMemoryType);
    allocator =
        reservedMemoryType.equalsIgnoreCase("direct")
            ? Allocators.directAllocator(reservedMemorySize, 32)
            : Allocators.heapAllocator(reservedMemorySize, 32);
    timestampProvider = tsProvider;
    this.inactivityDelay = inactivityDelayNs;

    for (TracingContextTracker.IntervalBlobListener listener :
        ServiceLoader.load(TracingContextTracker.IntervalBlobListener.class)) {
      blobListeners.add(listener);
    }
    if (inactivityDelay > 0) {
      initializeInactiveTrackerCleanup(inactivityCheckPeriodMs);
    }
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    ProfilerTracingContextTracker instance =
        new ProfilerTracingContextTracker(
            allocator, span, timestampProvider, sequencePruner, inactivityDelay);
    instance.setBlobListeners(blobListeners);
    if (inactivityDelay > 0) {
      delayQueue.offer(instance.asDelayed());
    }
    return instance;
  }
}
