package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

import datadog.trace.relocate.api.RatelimitedLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilerTracingContextTrackerFactory
    implements TracingContextTrackerFactory.Implementation {
  private static final Logger log =
      LoggerFactory.getLogger(ProfilerTracingContextTrackerFactory.class);
  private static final RatelimitedLogger warnlog =
      new RatelimitedLogger(
          LoggerFactory.getLogger(ProfilerTracingContextTrackerFactory.class), 30, TimeUnit.SECONDS);

  private static final StatsDClient statsd = StatsDAccessor.getStatsdClient();

  public static void register(ConfigProvider configProvider) {
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_TRACING_CONTEXT_ENABLED,
        ProfilingConfig.PROFILING_TRACING_CONTEXT_ENABLED_DEFAULT)) {
      long inactivityDelayNs =
          TimeUnit.SECONDS.toNanos(
              configProvider.getInteger(
                  ProfilingConfig.PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_SEC,
                  ProfilingConfig.PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_DEFAULT));
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
          new ProfilerTracingContextTrackerFactory(
              inactivityDelayNs, inactivityCheckPeriod, reservedMemorySize, reservedMemoryType));
    }
  }

  private final Allocator allocator;
  private final IntervalSequencePruner sequencePruner = new IntervalSequencePruner();
  private final ProfilerTracingContextTracker.TimeTicksProvider timeTicksProvider;

  private final ExpirationTracker expirationTracker;

  ProfilerTracingContextTrackerFactory(
      long inactivityDelayNs, long inactivityCheckPeriodMs, int reservedMemorySize) {
    this(inactivityDelayNs, inactivityCheckPeriodMs, reservedMemorySize, "heap");
  }

  ProfilerTracingContextTrackerFactory(
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
    this.expirationTracker = new ExpirationTracker(inactivityDelayNs, TimeUnit.MILLISECONDS.toNanos(inactivityCheckPeriodMs), TimeUnit.NANOSECONDS, Runtime.getRuntime().availableProcessors(), 500_000);
  }

  private static ProfilerTracingContextTracker.TimeTicksProvider getTicksProvider() {
    try {
      Class<?> clz =
          ProfilerTracingContextTracker.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
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
      return new ProfilerTracingContextTracker.TimeTicksProvider() {
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
    return ProfilerTracingContextTracker.TimeTicksProvider.SYSTEM;
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    ExpirationTracker.Expirable e = expirationTracker.track();
    if (!e.hasExpiration()) {
      warnlog.warn("Expiration tracking of profiling context failed. Span {} will not be tracked", span);
      return TracingContextTracker.EMPTY;
    }
    ProfilerTracingContextTracker instance =
        new ProfilerTracingContextTracker(allocator, span, timeTicksProvider, sequencePruner, e);

    e.setOnExpiredCallback(i -> instance.release());

    statsd.incrementCounter("tracing.context.spans.tracked");
    return instance;
  }
}
