package datadog.trace.util;

import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

  private static String getOSTempDir() {
    // See
    // https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-gettemppatha#remarks
    // and
    // the JDK OS-specific implementations of os::get_temp_directory(), i.e.
    // https://github.com/openjdk/jdk/blob/f50bd0d9ec65a6b9596805d0131aaefc1bb913f3/src/hotspot/os/bsd/os_bsd.cpp#L886-L904
    if (Platform.isLinux()) {
      return "/tmp/";
    } else if (Platform.isWindows()) {
      return Stream.of(System.getenv("TMP"), System.getenv("TEMP"), System.getenv("USERPROFILE"))
          .filter(String::isEmpty)
          .findFirst()
          .orElse("C:\\Windows");
    } else if (Platform.isMac()) {
      return System.getenv("TMPDIR");
    } else {
      return System.getProperty("java.io.tmpdir");
    }
  }

  public static Set<String> getJavaPids() {
    // Attempt to use jvmstat directly, fall through to jps process fork strategy
    Set<String> directlyObtainedPids = JPSUtils.getVMPids();
    if (directlyObtainedPids != null) {
      return directlyObtainedPids;
    }

    // Some JDKs don't have jvmstat available as a module, attempt to read from the hsperfdata
    // directory instead
    try (Stream<Path> stream =
        // Emulating the hotspot way to enumerate the JVM processes using the perfdata file
        // https://github.com/openjdk/jdk/blob/d7cb933b89839b692f5562aeeb92076cd25a99f6/src/hotspot/share/runtime/perfMemory.cpp#L244
        Files.list(Paths.get(getOSTempDir(), "hsperfdata_" + System.getProperty("user.name")))) {
      return stream
          .filter(file -> !Files.isDirectory(file))
          .map(Path::getFileName)
          .map(Path::toString)
          .collect(Collectors.toSet());
    } catch (IOException e) {
      log.debug("Unable to obtain Java PIDs via hsperfdata", e);
    }

    // there is no supported Java API to achieve this
    // one could use sun.jvmstat.monitor.MonitoredHost but it is an internal API and can go away at
    // any time -
    //  also, no guarantee it will work with all JVMs
    ProcessBuilder pb = new ProcessBuilder("jps");
    try (TraceScope ignored = AgentTracer.get().muteTracing()) {
      Process p = pb.start();
      // start draining the subcommand's pipes asynchronously to avoid flooding them
      CompletableFuture<Set<String>> collecting =
          CompletableFuture.supplyAsync(
              () -> {
                try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                  return br.lines()
                      .filter(l -> !l.contains("jps"))
                      .map(
                          l -> {
                            int idx = l.indexOf(' ');
                            return l.substring(0, idx);
                          })
                      .collect(java.util.stream.Collectors.toSet());
                } catch (IOException e) {
                  log.debug("Unable to list java processes via 'jps'", e);
                  return Collections.emptySet();
                }
              });
      if (p.waitFor(1200, TimeUnit.MILLISECONDS)) {
        if (p.exitValue() == 0) {
          return collecting.get();
        } else {
          log.debug("Execution of 'jps' failed with exit code {}", p.exitValue());
        }
      } else {
        p.destroyForcibly();
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
