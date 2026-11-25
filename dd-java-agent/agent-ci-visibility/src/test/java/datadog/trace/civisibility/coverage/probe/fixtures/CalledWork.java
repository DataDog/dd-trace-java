package datadog.trace.civisibility.coverage.probe.fixtures;

/** Short methods that {@link WindowSpanningWorkload} calls. */
public final class CalledWork {

  private CalledWork() {}

  public static int work(int count) {
    int sum = 0;
    for (int i = 0; i < count; i++) {
      sum += i;
    }
    return sum;
  }

  public static int tail(int total) {
    return total % 7 == 0 ? 1 : 2;
  }
}
