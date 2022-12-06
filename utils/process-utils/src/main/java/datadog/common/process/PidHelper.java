package datadog.common.process;

import de.thetaphi.forbiddenapis.SuppressForbidden;

import java.util.function.Supplier;

/**
 * Get PID In reasonably cross-platform way
 *
 * <p>FIXME: ideally we would like to be able to send PID with root span as well, but currently this
 * end up causing packaging problems. We should revisit this later.
 */
@SuppressForbidden
public class PidHelper {
  public static final String PID_TAG = "process_id";
  public static Long PID = null;

  public static synchronized void computeIfAbsent(Supplier<Long> supplier) {
    if (null == PID) {
      PID = supplier.get();
    }
  }
}
