package datadog.trace.util;

import datadog.trace.api.Platform;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

  public static Set<String> getJavaPids() {
    // there is no supported Java API to achieve this
    // one could use sun.jvmstat.monitor.MonitoredHost but it is an internal API and can go away at
    // any time -
    //  also, no guarantee it will work with all JVMs
    ProcessBuilder pb = new ProcessBuilder("jps");
    try {
      Process p = pb.start();
      if (p.waitFor(500, TimeUnit.MILLISECONDS)) {
        if (p.exitValue() == 0) {
          try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            return br.lines()
                .filter(l -> !l.contains("jps"))
                .map(
                    l -> {
                      int idx = l.indexOf(' ');
                      return l.substring(0, idx);
                    })
                .collect(java.util.stream.Collectors.toSet());
          }
        } else {
          log.debug("Execution of 'jps' failed with exit code {}", p.exitValue());
        }
      } else {
        log.debug("Execution of 'jps' timed out");
      }
    } catch (Exception e) {
      log.debug("Unable to list java processes via 'jps'", e);
    }
    return Collections.emptySet();
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
