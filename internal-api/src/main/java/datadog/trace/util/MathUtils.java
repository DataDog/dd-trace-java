package datadog.trace.util;

import java.util.concurrent.atomic.AtomicLong;

public class MathUtils {
  /**
   * Atomically decrements the long if its value would not go below minimum
   *
   * @return if the long was decremented by this call
   */
  public static boolean boundedDecrement(AtomicLong value, long minumum) {
    long previous;
    long next;
    do {
      previous = value.get();
      next = previous - 1;

      if (next < minumum) {
        return false;
      }
    } while (!value.compareAndSet(previous, next));
    return true;
  }
}
