package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.StatsDClient;
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
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PerSpanTracingContextTrackerFactory
    implements TracingContextTrackerFactory.Implementation {
  private static final Logger log =
      LoggerFactory.getLogger(PerSpanTracingContextTrackerFactory.class);

  private final DelayQueue<TracingContextTracker.DelayedTracker> delayQueue = new DelayQueue<>();
  private final StatsDClient statsd = StatsDAccessor.getStatsdClient();

  public static boolean isEnabled(ConfigProvider configProvider) {
    return configProvider.getBoolean(
        ProfilingConfig.PROFILING_TRACING_CONTEXT_ENABLED,
        ProfilingConfig.PROFILING_TRACING_CONTEXT_ENABLED_DEFAULT);
  }

  public static void register(ConfigProvider configProvider) {
    if (isEnabled(configProvider)) {
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

      int inactivityCheckPeriod =
          configProvider.getInteger(
              ProfilingConfig.PROFILING_TRACING_CONTEXT_SPAN_INACTIVITY_CHECK,
              ProfilingConfig.PROFILING_TRACING_CONTEXT_SPAN_INACTIVITY_CHECK_DEFAULT);

      TracingContextTrackerFactory.registerImplementation(
          new PerSpanTracingContextTrackerFactory(
              inactivityDelayNs, inactivityCheckPeriod, reservedMemorySize, reservedMemoryType));
    }
  }

  private void initializeInactiveTrackerCleanup(long checkPeriodMs) {
    // the task should not run more often than once per 100ms
    long refreshRateMs = Math.max(100, checkPeriodMs);
    AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
        target -> {
          int capacity = 500;
          Collection<TracingContextTracker.DelayedTracker> timeouts = new ArrayList<>(capacity);
          int drainedAll = 0;
          int drained = 0;
          while ((drained = target.drainTo(timeouts, capacity)) > 0) {
            if (log.isDebugEnabled()) {
              log.debug("Drained {} inactive trackers", drained);
            }
            timeouts.forEach(TracingContextTracker.DelayedTracker::cleanup);
            timeouts.clear();
            drainedAll += drained;
          }
          if (drainedAll > 0) {
            statsd.count("tracing.context.spans.drained_inactive", drainedAll);
          }
        },
        delayQueue,
        refreshRateMs,
        refreshRateMs,
        TimeUnit.MILLISECONDS);
  }

  private final Allocator allocator;
  private final IntervalSequencePruner sequencePruner = new IntervalSequencePruner();
  private final PerSpanTracingContextTracker.TimeTicksProvider timeTicksProvider;
  private final long inactivityDelay;

  PerSpanTracingContextTrackerFactory(
      long inactivityDelayNs, long inactivityCheckPeriodMs, int reservedMemorySize) {
    this(inactivityDelayNs, inactivityCheckPeriodMs, reservedMemorySize, "heap");
  }

  PerSpanTracingContextTrackerFactory(
      long inactivityDelayNs,
      long inactivityCheckPeriodMs,
      int reservedMemorySize,
      String reservedMemoryType) {
    timeTicksProvider = getTicksProvider();
    log.debug("Tracing Context Tracker Memory Type: {}", reservedMemoryType);
    allocator =
        reservedMemoryType.equalsIgnoreCase("direct")
            ? Allocators.directAllocator(reservedMemorySize, 32)
            : Allocators.heapAllocator(reservedMemorySize, 32);
    this.inactivityDelay = inactivityDelayNs;

    if (inactivityDelay > 0) {
      initializeInactiveTrackerCleanup(inactivityCheckPeriodMs);
    }
  }

  private static PerSpanTracingContextTracker.TimeTicksProvider getTicksProvider() {
    try {
      Class<?> clz =
          PerSpanTracingContextTracker.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
      MethodHandle mh;
      long frequency = 0L;
      try {
        mh = MethodHandles.lookup().findStatic(clz, "getJVM", MethodType.methodType(clz));
        Object jvm = mh.invoke();
        mh =
            MethodHandles.lookup()
                .findVirtual(clz, "getTicksFrequency", MethodType.methodType(long.class));
        mh = mh.bindTo(jvm);
        frequency = (long) mh.invokeWithArguments();
      } catch (NoSuchMethodException ignored) {
        // the method is available since JDK11 only
      }
      mh = MethodHandles.lookup().findStatic(clz, "counterTime", MethodType.methodType(long.class));
      // sanity check to fail early if the method handle invocation does not work
      long ticks = (long) mh.invokeExact();

      MethodHandle fixedMh = mh;
      long fixedFrequency = frequency;

      log.info("Using JFR time ticks provider");
      return new PerSpanTracingContextTracker.TimeTicksProvider() {
        @Override
        public long ticks() {
          try {
            return (long) fixedMh.invokeExact();
          } catch (Throwable ignored) {
          }
          return Long.MIN_VALUE;
        }

        @Override
        public long frequency() {
          return fixedFrequency;
        }
      };
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.warn("Failed to access JFR timestamps. Falling back to system nanotime.", t);
      } else {
        log.warn("Failed to access JFR timestamps. Falling back to system nanotime.");
      }
    }
    return PerSpanTracingContextTracker.TimeTicksProvider.SYSTEM;
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    if (span != null && span.eligibleForDropping()) {
      return TracingContextTracker.EMPTY;
    }
    PerSpanTracingContextTracker instance =
        new PerSpanTracingContextTracker(
            allocator, span, timeTicksProvider, sequencePruner, inactivityDelay);
    if (inactivityDelay > 0) {
      statsd.incrementCounter("tracing.context.spans.tracked");
      delayQueue.offer(instance.asDelayed());
    }
    return instance;
  }
}
