package datadog.trace.util;

import java.util.function.Function;

/** Use standard API to retrieve the PID of a child process on Java9+. */
public final class JDK9ProcessPidFunction implements Function<Process, String> {
  @Override
  public String apply(Process process) {
    try {
      return Long.toString(process.pid());
    } catch (Throwable e) {
      return "";
    }
  }
}
