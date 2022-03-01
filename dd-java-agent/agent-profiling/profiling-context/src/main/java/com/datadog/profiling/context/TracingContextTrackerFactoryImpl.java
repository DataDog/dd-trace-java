package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.profiling.CustomEventAccess;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static volatile CustomEventAccess EVENT_ACCESS_REF = null;

  public static void register(CustomEventAccess events) {
    EVENT_ACCESS_REF = events;
    TracingContextTrackerFactory.registerImplementation(Singleton.INSTANCE);
  }

  private final Allocator allocator = Allocators.directAllocator(16 * 1024 * 1024, 32);
  private final CustomEventAccess eventAccess;

  private TracingContextTrackerFactoryImpl() {
    this.eventAccess = EVENT_ACCESS_REF != null ? EVENT_ACCESS_REF : CustomEventAccess.NULL;
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    return new TracingContextTrackerImpl(allocator, span, eventAccess);
  }
}
