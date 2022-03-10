package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
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

  private ProfilerTracingContextTrackerFactory() {
    for (TracingContextTracker.IntervalBlobListener listener :
        ServiceLoader.load(TracingContextTracker.IntervalBlobListener.class)) {
      blobListeners.add(listener);
    }
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    ProfilerTracingContextTracker instance =
        new ProfilerTracingContextTracker(allocator, span, sequencePruner);
    instance.setBlobListeners(blobListeners);
    return instance;
  }
}
