package datadog.trace.util;

import datadog.trace.api.Platform;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Get PID in reasonably cross-platform way. */
@SuppressForbidden
public final class PidHelper {
  private static final Logger log = LoggerFactory.getLogger(PidHelper.class);

  private static final String PID = findPid();

  private static final long PID_AS_LONG = parsePid();

  public static String getPid() {
    return PID;
  }

  /** Returns 0 if the PID is not a number. */
  public static long getPidAsLong() {
    return PID_AS_LONG;
  }

  private static String findPid() {
    String pid = "";
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        pid =
            Strings.trim(
                ((Supplier<String>)
                        Class.forName("datadog.trace.util.JDK9PidSupplier")
                            .getDeclaredConstructor()
                            .newInstance())
                    .get());
      } catch (Throwable e) {
        log.debug("JDK9PidSupplier not available", e);
      }
    }
    if (pid.isEmpty()) {
      try {
        // assumption: first part of runtime vmId is our process id
        String vmId = ManagementFactory.getRuntimeMXBean().getName();
        int pidEnd = vmId.indexOf('@');
        if (pidEnd > 0) {
          pid = vmId.substring(0, pidEnd).trim();
        }
      } catch (Throwable e) {
        log.debug("Process id not available", e);
      }
    }
    return pid;
  }

  private static long parsePid() {
    if (!PID.isEmpty()) {
      try {
        return Long.parseLong(PID);
      } catch (NumberFormatException e) {
        log.warn("Cannot parse PID {} as number. Default to 0", PID, e);
      }
    }
    return 0L;
  }
}
