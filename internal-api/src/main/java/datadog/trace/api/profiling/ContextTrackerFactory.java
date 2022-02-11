package datadog.trace.api.profiling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class ContextTrackerFactory {
  private static final Logger log = LoggerFactory.getLogger(ContextTrackerFactory.class);

  public interface Implementation {
    Implementation EMPTY = new Implementation() {
      public ContextTracker instance() {
        return ContextTracker.EMPTY;
      }
    };

    ContextTracker instance();
  }

  private static final ContextTrackerFactory INSTANCE = new ContextTrackerFactory();

  private volatile Implementation implementation = Implementation.EMPTY;
  private final AtomicReferenceFieldUpdater<ContextTrackerFactory, Implementation> implFieldUpdater = AtomicReferenceFieldUpdater.newUpdater(ContextTrackerFactory.class, Implementation.class, "implementation");

  public static boolean registerImplementation(Implementation factoryImplementation) {
    try {
      return INSTANCE.implFieldUpdater.compareAndSet(INSTANCE, Implementation.EMPTY, factoryImplementation);
    } catch (Throwable t) {
      log.warn("Failed to register a profiling context implementation", t);
    }
    return false;
  }

  public static ContextTracker instance() {
    return INSTANCE.implementation.instance();
  }
}
