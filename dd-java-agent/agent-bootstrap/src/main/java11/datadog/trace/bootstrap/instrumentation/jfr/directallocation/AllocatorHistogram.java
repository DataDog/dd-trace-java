package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;

public class AllocatorHistogram extends ClassValue<AtomicLong[]> {
  private final List<WeakReference<Class<?>>> refs = new CopyOnWriteArrayList<>();

  private final EventType eventType;
  private final Runnable eventHook;

  AllocatorHistogram() {
    eventType = EventType.getEventType(DirectAllocationTotalEvent.class);
    eventHook = this::emit;
    JfrHelper.addPeriodicEvent(DirectAllocationTotalEvent.class, eventHook);
  }

  /** Remove this instance from JFR periodic events callbacks */
  void deregister() {
    FlightRecorder.removePeriodicEvent(eventHook);
  }

  public boolean record(Class<?> caller, DirectAllocationSource allocationSource, long allocated) {
    if (!eventType.isEnabled()) {
      return false;
    }
    long total = get(caller)[allocationSource.ordinal()].getAndAdd(allocated);
    return total == 0;
  }

  @Override
  protected AtomicLong[] computeValue(Class<?> type) {
    refs.add(new WeakReference<>(type));
    AtomicLong[] values = new AtomicLong[DirectAllocationSource.VALUES.length];
    Arrays.setAll(values, i -> new AtomicLong());
    return values;
  }

  private void emit() {
    for (WeakReference<Class<?>> ref : refs) {
      Class<?> clazz = ref.get();
      if (clazz != null) {
        AtomicLong[] counts = get(clazz);
        if (counts != null) {
          String name = clazz.getName();
          for (DirectAllocationSource source : DirectAllocationSource.VALUES) {
            long size = counts[source.ordinal()].getAndSet(0);
            if (size > 0) {
              new DirectAllocationTotalEvent(name, source.name(), size).commit();
            }
          }
        }
      }
    }
  }
}
