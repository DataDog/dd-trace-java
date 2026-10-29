package com.example.regexbench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

  private static final Method[] REGEX_COUNTERS = regexCounters();

  @GetMapping("/admin/stats")
  public String stats() {
    StringBuilder sb = new StringBuilder(256);
    long cpu = -1;
    java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    if (os instanceof com.sun.management.OperatingSystemMXBean) {
      cpu = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
    }
    long gcCount = 0;
    long gcMs = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      gcCount += Math.max(0, gc.getCollectionCount());
      gcMs += Math.max(0, gc.getCollectionTime());
    }
    line(sb, "cpuNanos", cpu);
    line(sb, "gcCount", gcCount);
    line(sb, "gcMs", gcMs);
    line(sb, "regexHits", counter(0));
    line(sb, "regexMisses", counter(1));
    line(sb, "regexAdmissions", counter(2));
    return sb.toString();
  }

  private static void line(StringBuilder sb, String key, long value) {
    sb.append(key).append('=').append(value).append('\n');
  }

  private static long counter(int i) {
    if (REGEX_COUNTERS == null) {
      return -1;
    }
    try {
      return ((Number) REGEX_COUNTERS[i].invoke(null)).longValue();
    } catch (ReflectiveOperationException e) {
      return -1;
    }
  }

  private static Method[] regexCounters() {
    try {
      Class<?> c = Class.forName("datadog.trace.bootstrap.regex.RegexCache", false, null);
      return new Method[] {c.getMethod("hits"), c.getMethod("misses"), c.getMethod("admissions")};
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }
}
