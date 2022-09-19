package com.datadog.profiling.leakmonitor;

import java.lang.management.GarbageCollectorMXBean;
import java.util.Arrays;

public class GarbageCollectorMXBeans {

  /**
   * "Non-transient" should be taken to mean the measure consists entirely (in generational
   * collectors) or partially (in non-generational collectors) of objects not eligible for garbage
   * collection.
   */
  public static boolean isNonTransient(GarbageCollectorMXBean bean) {
    // this is error-prone because the names change according to JDK version and vendor
    switch (bean.getName()) {
      case "MarkSweepCompact":
      case "MarkSweep":
      case "PS MarkSweep":
      case "G1 Old Generation":
      case "G1 Old Gen":
      case "Shenandoah Cycles":
      case "ZGC Cycles":
        return true;
      default:
        return false;
    }
  }

  /**
   * Some GCs report multiple pools, but we only want the oldest generation (or the only generation
   * when the GC is non-generational).
   */
  public static Object[][] filterPoolNames(GarbageCollectorMXBean bean) {
    String[] memoryPoolNames = bean.getMemoryPoolNames();
    Object[][] pools = new Object[memoryPoolNames.length][];
    int numMonitoredPools = 0;
    for (String poolName : memoryPoolNames) {
      switch (poolName) {
        case "Tenured Gen":
        case "PS Old Gen":
        case "G1 Old Gen":
        case "Old Gen":
        case "Shenandoah":
        case "ZHeap":
          pools[numMonitoredPools++] = new Object[] {poolName};
          break;
        default:
      }
    }
    return Arrays.copyOf(pools, numMonitoredPools);
  }
}
