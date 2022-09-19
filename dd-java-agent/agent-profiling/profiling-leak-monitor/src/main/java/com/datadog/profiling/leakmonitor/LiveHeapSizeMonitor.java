package com.datadog.profiling.leakmonitor;

import com.datadog.profiling.controller.OngoingRecording;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import javax.management.NotificationEmitter;

public class LiveHeapSizeMonitor {

  public LiveHeapSizeMonitor(OngoingRecording recording) {
    this(new MovingAverageUsedHeapTrendAnalyzer(50, 5), new ToggleOldObjectSample(recording));
  }

  public LiveHeapSizeMonitor(Analyzer analyzer, Action action) {
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      maybeSubscribe(gc, analyzer, action);
    }
  }

  private static void maybeSubscribe(GarbageCollectorMXBean gc, Analyzer analyzer, Action action) {
    String name = gc.getName();
    if (gc instanceof NotificationEmitter && GarbageCollectorMXBeans.isNonTransient(gc)) {
      ((NotificationEmitter) gc)
          .addNotificationListener(
              new GcMonitor(analyzer, GarbageCollectorMXBeans.filterPoolNames(gc), action),
              null,
              name);
    }
  }
}
