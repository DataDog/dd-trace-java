package datadog.opentracing.jfr.openjdk;

import java.util.concurrent.Callable;

public class ThreadCpuTime {
  private static volatile Callable<Long> CPU_TIME_PROVIDER;

  // must use Callable here since initialization is invoked from Agent which needs to be Java 7 compatible
  static void initialize(Callable<Long> provider) {
    CPU_TIME_PROVIDER = provider;
  }

  static long get() {
    try {
      return CPU_TIME_PROVIDER == null ? Long.MIN_VALUE : CPU_TIME_PROVIDER.call();
    } catch (Exception ignored) {}
    return Long.MIN_VALUE;
  }
}
