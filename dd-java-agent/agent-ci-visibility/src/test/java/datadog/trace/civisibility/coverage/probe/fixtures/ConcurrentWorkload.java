package datadog.trace.civisibility.coverage.probe.fixtures;

/**
 * Code that several workers run at once. Each worker takes its own path through {@link #common} and
 * calls a method no other worker calls. The class has no initializer, so all its lines belong to
 * the window that runs them.
 */
public final class ConcurrentWorkload {

  public static final int WORKERS = 3;

  private ConcurrentWorkload() {}

  public static int run(int worker) {
    int result = common(worker);
    if (worker == 0) {
      result += first();
    } else if (worker == 1) {
      result += second();
    } else {
      result += third();
    }
    return result;
  }

  private static int common(int worker) {
    int value = worker * 3;
    if (value > 2) {
      value -= 1;
    } else {
      value += 1;
    }
    return value;
  }

  private static int first() {
    int sum = 0;
    for (int i = 0; i < 3; i++) {
      sum += i;
    }
    return sum;
  }

  private static int second() {
    StringBuilder text = new StringBuilder();
    text.append("second");
    return text.length();
  }

  private static int third() {
    int[] values = {1, 2, 3};
    return values[2];
  }
}
