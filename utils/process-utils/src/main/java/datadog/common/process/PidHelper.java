package datadog.common.process;

import datadog.trace.api.Platform;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Get PID In reasonably cross-platform way
 *
 * <p>FIXME: ideally we would like to be able to send PID with root span as well, but currently this
 * end up causing packaging problems. We should revisit this later.
 */
@SuppressForbidden
public class PidHelper {
  private static final Logger log = LoggerFactory.getLogger(PidHelper.class);

  public static final String PID_TAG = "process_id";
  public static final Long PID = getPid();

  private static Long getPid() {
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        final Class<?> processHandler = Class.forName("java.lang.ProcessHandle");
        final Object object = processHandler.getMethod("current").invoke(null);
        return (Long) processHandler.getMethod("pid").invoke(object);
      } catch (final Exception e) {
        log.debug("Cannot get PID through JVM API, trying POSIX instead", e);
      }
    }

    try {
      final POSIX posix = POSIXFactory.getPOSIX();
      return (long) posix.getpid();
    } catch (final Exception e) {
      log.debug("Cannot get PID through POSIX API, giving up", e);
    }

    return null;
  }
}
