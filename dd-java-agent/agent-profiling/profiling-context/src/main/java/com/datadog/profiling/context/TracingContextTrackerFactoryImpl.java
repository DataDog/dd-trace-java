package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.profiling.CustomEventAccess;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

public final class TracingContextTrackerFactoryImpl
    implements TracingContextTrackerFactory.Implementation {
  private static final Logger log = LoggerFactory.getLogger(TracingContextTrackerFactoryImpl.class);

  /*
  The singleton instance needs to be wrapped in the 'singleton' class to delay the creation of the
  single instance only upon calling `register()`
   */
  private static final class Singleton {
    private static final TracingContextTrackerFactoryImpl INSTANCE =
        new TracingContextTrackerFactoryImpl();
  }

  public static void register(CustomEventAccess events) {
    TracingContextTrackerFactory.registerImplementation(Singleton.INSTANCE);
  }

  private final Allocator allocator = Allocators.directAllocator(16 * 1024 * 1024, 32);
  private final Set<TracingContextTracker.IntervalBlobListener> blobListeners = new HashSet<>();
  private final IntervalSequencePruner sequencePruner = new IntervalSequencePruner();

  private TracingContextTrackerFactoryImpl() {
    for (TracingContextTracker.IntervalBlobListener listener : ServiceLoader.load(TracingContextTracker.IntervalBlobListener.class)) {
      blobListeners.add(listener);
    }
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    TracingContextTrackerImpl instance = new TracingContextTrackerImpl(allocator, span, sequencePruner);
    instance.setBlobListeners(blobListeners);
    return instance;
  }
}
