package datadog.trace.api.profiling;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilingContextTrackerFactory {
  private static final Logger log = LoggerFactory.getLogger(ProfilingContextTrackerFactory.class);

  public interface Implementation {
    Implementation EMPTY =
        new Implementation() {
          public ProfilingContextTracker instance() {
            return ProfilingContextTracker.EMPTY;
          }
        };

    ProfilingContextTracker instance();
  }

  private static final ProfilingContextTrackerFactory INSTANCE =
      new ProfilingContextTrackerFactory();

  private volatile Implementation implementation = Implementation.EMPTY;
  private final AtomicReferenceFieldUpdater<ProfilingContextTrackerFactory, Implementation>
      implFieldUpdater =
          AtomicReferenceFieldUpdater.newUpdater(
              ProfilingContextTrackerFactory.class, Implementation.class, "implementation");

  public static boolean registerImplementation(Implementation factoryImplementation) {
    try {
      return INSTANCE.implFieldUpdater.compareAndSet(
          INSTANCE, Implementation.EMPTY, factoryImplementation);
    } catch (Throwable t) {
      log.warn("Failed to register a profiling context implementation", t);
    }
    return false;
  }

  public static ProfilingContextTracker instance() {
    return INSTANCE.implementation.instance();
  }
}
