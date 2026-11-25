package datadog.trace.civisibility.coverage.probe.fixtures;

/**
 * Methods that keep running while the code they call opens and closes coverage windows. The work
 * they call is in {@link CalledWork}, so that the lines of the long-running methods are the lines
 * of this file. The class has no initializer, so all its lines belong to the owners that run them.
 */
public final class WindowSpanningWorkload {

  private WindowSpanningWorkload() {}

  /** Does some work, runs {@code middle}, then does more work. */
  public static int around(Runnable middle) {
    int before = CalledWork.work(3);
    if (before < 0) {
      return -1;
    }
    middle.run();
    int after = CalledWork.work(4);
    if (after < 0) {
      return -2;
    }
    return before + after;
  }

  /** Runs the steps in turn, with work of its own before each, and ends with more work. */
  public static int between(Runnable[] steps) {
    int total = 0;
    for (Runnable step : steps) {
      total += CalledWork.work(2);
      if (total < 0) {
        return -1;
      }
      step.run();
    }
    return total + CalledWork.tail(total);
  }
}
