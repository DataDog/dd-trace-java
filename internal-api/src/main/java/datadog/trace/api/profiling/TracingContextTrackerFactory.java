package datadog.trace.api.profiling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.util.AgentTaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final DelayQueue<TracingContextTracker.DelayedTracker> DELAY_QUEUE = new DelayQueue<>();

  public static boolean registerImplementation(Implementation factoryImplementation) {
    try {
      boolean result = INSTANCE.implFieldUpdater.compareAndSet(
          INSTANCE, Implementation.EMPTY, factoryImplementation);
      if (result) {
        initializeInactiveTrackerCleanup();
      }
      return result;
    } catch (Throwable t) {
      log.warn("Failed to register a profiling context implementation", t);
    }
    return false;
  }

  private static void initializeInactiveTrackerCleanup() {
    AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(new AgentTaskScheduler.Task<DelayQueue<TracingContextTracker.DelayedTracker>>() {
      @Override
      public void run(DelayQueue<TracingContextTracker.DelayedTracker> target) {
        Collection<TracingContextTracker.DelayedTracker> timeouts = new ArrayList<>(500);
        int drained = 0;
        do {
          log.info("Inactive Tracker Cleanup (size={})", target.size());
          synchronized (target) {
            drained = target.drainTo(timeouts);
          }
          log.info("Drained {} trackers", drained);
          Iterator<TracingContextTracker.DelayedTracker> iterator = timeouts.iterator();
          while (iterator.hasNext()) {
            iterator.next().cleanup();
            iterator.remove();
          }
        } while (drained > 0);
      }
    }, DELAY_QUEUE, 5, 5, TimeUnit.SECONDS);
  }

  public static TracingContextTracker instance(AgentSpan span) {
    TracingContextTracker instance = INSTANCE.implementation.instance(span);
    synchronized (DELAY_QUEUE) {
      DELAY_QUEUE.offer(instance.asDelayed());
    }
    return instance;
  }
}
