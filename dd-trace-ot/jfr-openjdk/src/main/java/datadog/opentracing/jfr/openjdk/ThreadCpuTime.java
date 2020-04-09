package datadog.opentracing.jfr.openjdk;

import java.lang.management.ManagementFactory;
import java.util.function.Supplier;

public class ThreadCpuTime {
  private static volatile Supplier<Long> CPU_TIME_PROVIDER;

  static void initialize() {
    CPU_TIME_PROVIDER = ManagementFactory.getThreadMXBean()::getCurrentThreadCpuTime;
  }

  static long get() {
    return CPU_TIME_PROVIDER == null ? Long.MIN_VALUE : CPU_TIME_PROVIDER.get();
  }
}
