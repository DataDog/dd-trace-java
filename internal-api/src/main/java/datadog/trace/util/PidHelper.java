package datadog.trace.util;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import datadog.trace.config.inversion.ConfigHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
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

  @SuppressWarnings("unchecked")
  private static String findPid() {
    String pid = "";
    if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
      try {
        pid =
            ((Supplier<String>)
                    Class.forName("datadog.trace.util.JDK9PidSupplier")
                        .getDeclaredConstructor()
                        .newInstance())
                .get()
                .trim();
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

  private static String getTempDir() {
    if (!JavaVirtualMachine.isJ9()) {
      // See
      // https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-gettemppatha#remarks
      // and
      // the JDK OS-specific implementations of os::get_temp_directory(), i.e.
      // https://github.com/openjdk/jdk/blob/f50bd0d9ec65a6b9596805d0131aaefc1bb913f3/src/hotspot/os/bsd/os_bsd.cpp#L886-L904
      if (OperatingSystem.isLinux()) {
        return "/tmp";
      } else if (OperatingSystem.isWindows()) {
        return Stream.of("TMP", "TEMP", "USERPROFILE")
            .map(ConfigHelper::env)
            .filter(Objects::nonNull)
            .filter(((Predicate<String>) String::isEmpty).negate())
            .findFirst()
            .orElse("C:\\Windows");
      } else if (OperatingSystem.isMacOs()) {
        return ConfigHelper.env("TMPDIR");
      } else {
        return SystemProperties.get("java.io.tmpdir");
      }
    } else {
      try {
        https: // github.com/eclipse-openj9/openj9/blob/196082df056a990756a5571bfac29585fbbfbb42/jcl/src/java.base/share/classes/openj9/internal/tools/attach/target/IPC.java#L351
        return (String)
            Class.forName("openj9.internal.tools.attach.target.IPC")
                .getDeclaredMethod("getTmpDir")
                .invoke(null);
      } catch (Throwable t) {
        // Fall back to constants based on J9 source code, may not have perfect coverage
        String tmpDir = SystemProperties.get("java.io.tmpdir");
        if (tmpDir != null && !tmpDir.isEmpty()) {
          return tmpDir;
        } else if (OperatingSystem.isWindows()) {
          return "C:\\Documents";
        } else {
          return "/tmp";
        }
      }
    }
  }

  private static Path getJavaProcessesDir() {
    if (JavaVirtualMachine.isJ9()) {
      // J9 uses a different temporary directory AND subdirectory for storing jps / attach-related
      // info
      // https://github.com/eclipse-openj9/openj9/blob/196082df056a990756a5571bfac29585fbbfbb42/jcl/src/java.base/share/classes/openj9/internal/tools/attach/target/CommonDirectory.java#L94
      return Paths.get(getTempDir(), ".com_ibm_tools_attach");
    } else {
      // Emulating the hotspot way to enumerate the JVM processes using the perfdata file
      // https://github.com/openjdk/jdk/blob/d7cb933b89839b692f5562aeeb92076cd25a99f6/src/hotspot/share/runtime/perfMemory.cpp#L244
      return Paths.get(getTempDir(), "hsperfdata_" + SystemProperties.get("user.name"));
    }
  }

  public static Set<String> getJavaPids() {
    try (Stream<Path> stream = Files.list(getJavaProcessesDir())) {
      return stream
          .map(Path::getFileName)
          .map(Path::toString)
          .filter(
              (name) -> {
                // On J9, additional metadata files are present alongside files named $PID.
                // Additionally, the contents of the ps dir are files with process ID files for
                // Hotspot,
                // but they are directories for J9.
                // This also makes sense as defensive programming.
                if (name.isEmpty()) {
                  return false;
                }
                char c = name.charAt(0);
                if (c < '0' || c > '9') {
                  // Short-circuit - let's not parse as long something that is definitely not a long
                  // number
                  return false;
                }
                long pid = -1;
                try {
                  pid = Long.parseLong(name);
                } catch (NumberFormatException ignored) {
                }
                return pid != -1;
              })
          .collect(Collectors.toSet());
    } catch (IOException e) {
      log.debug("Unable to obtain Java PIDs", e);
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
