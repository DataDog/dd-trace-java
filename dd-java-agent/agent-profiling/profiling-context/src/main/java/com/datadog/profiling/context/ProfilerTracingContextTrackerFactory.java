package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilerTracingContextTrackerFactory
    implements TracingContextTrackerFactory.Implementation {
  private static final Logger log =
      LoggerFactory.getLogger(ProfilerTracingContextTrackerFactory.class);

  /*
  The singleton instance needs to be wrapped in the 'singleton' class to delay the creation of the
  single instance only upon calling `register()`
   */
  private static final class Singleton {
    private static final ProfilerTracingContextTrackerFactory INSTANCE =
        new ProfilerTracingContextTrackerFactory();
  }

  public static void register() {
    TracingContextTrackerFactory.registerImplementation(Singleton.INSTANCE);
  }

  private final Allocator allocator = Allocators.directAllocator(16 * 1024 * 1024, 32);
  private final Set<TracingContextTracker.IntervalBlobListener> blobListeners = new HashSet<>();
  private final IntervalSequencePruner sequencePruner = new IntervalSequencePruner();
  private final ProfilerTracingContextTracker.TimestampProvider timestampProvider;
  private final long inactivityDelay;

  private ProfilerTracingContextTrackerFactory() {
    ProfilerTracingContextTracker.TimestampProvider tsProvider = System::nanoTime;
    try {
      Class<?> clz =
          ProfilerTracingContextTracker.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
      MethodHandle mh = MethodHandles.lookup().findStatic(clz, "counterTime", MethodType.methodType(long.class));
      tsProvider = () -> {
        try {
          return (long)mh.invokeExact();
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
    timestampProvider = tsProvider;
    int inactivitySecs = ConfigProvider.getInstance().getInteger(ProfilingConfig.PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_SEC, ProfilingConfig.PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_DEFAULT);
    this.inactivityDelay = TimeUnit.NANOSECONDS.convert(inactivitySecs, TimeUnit.SECONDS);

    log.info("Profiler Tracing Context Tracker Inactivity Timeout = {}s", inactivitySecs);
    for (TracingContextTracker.IntervalBlobListener listener :
        ServiceLoader.load(TracingContextTracker.IntervalBlobListener.class)) {
      blobListeners.add(listener);
    }
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    ProfilerTracingContextTracker instance =
        new ProfilerTracingContextTracker(allocator, span, timestampProvider, sequencePruner, inactivityDelay);
    instance.setBlobListeners(blobListeners);
    return instance;
  }
}
