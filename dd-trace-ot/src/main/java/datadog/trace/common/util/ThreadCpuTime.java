package datadog.trace.common.util;

import java.util.concurrent.Callable;

public class ThreadCpuTime {
  private static volatile Callable<Long> cpuTimeProvider =
      new Callable<Long>() {
        @Override
        public Long call() throws Exception {
          return Long.MIN_VALUE;
        }
      };

  // must use Callable here since initialization is invoked from Agent which needs to be Java 7
  // compatible
  public static void initialize(Callable<Long> provider) {
    cpuTimeProvider = provider;
  }

  public static long get() {
    try {
      return cpuTimeProvider.call();
    } catch (Exception ignored) {
    }
    return Long.MIN_VALUE;
  }
}
