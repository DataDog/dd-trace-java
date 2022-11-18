package datadog.trace.api.profiling;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory class for {@linkplain TracingContextTracker} instances.<br>
 * This class is actually just a shared scaffolding and the actual implementation needs to register
 * itself by calling {@linkplain
 * TracingContextTrackerFactory#registerImplementation(Implementation)}
 */
public final class TracingContextTrackerFactory {
  private static final Logger log = LoggerFactory.getLogger(TracingContextTrackerFactory.class);

  public interface Implementation {
    Implementation EMPTY =
        new Implementation() {
          public TracingContextTracker instance(AgentSpan span) {
            return TracingContextTracker.EMPTY;
          }
        };

    TracingContextTracker instance(AgentSpan span);
  }

  private static final TracingContextTrackerFactory INSTANCE = new TracingContextTrackerFactory();

  private volatile Implementation implementation = Implementation.EMPTY;
  private final AtomicReferenceFieldUpdater<TracingContextTrackerFactory, Implementation>
      implFieldUpdater =
          AtomicReferenceFieldUpdater.newUpdater(
              TracingContextTrackerFactory.class, Implementation.class, "implementation");

  public static boolean registerImplementation(Implementation factoryImplementation) {
    try {
      return INSTANCE.implFieldUpdater.compareAndSet(
          INSTANCE, Implementation.EMPTY, factoryImplementation);
    } catch (Throwable t) {
      log.warn("Failed to register a profiling context implementation", t);
    }
    return false;
  }

  static void removeImplementation(Implementation implementation) {
    INSTANCE.implFieldUpdater.compareAndSet(INSTANCE, implementation, Implementation.EMPTY);
  }

  public static boolean isTrackingAvailable() {
    // return true if a non-dummy implementation has been registered
    return INSTANCE.implementation != Implementation.EMPTY;
  }

  /**
   * Create a new tracing context tracker associated with the given span
   *
   * @param span the span to associate the tracker with
   * @return a new {@linkplain TracingContextTracker} instance
   */
  public static TracingContextTracker instance(AgentSpan span) {
    return INSTANCE.implementation.instance(span);
  }
}
