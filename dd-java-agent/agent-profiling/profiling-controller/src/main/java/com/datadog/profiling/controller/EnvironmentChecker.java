package com.datadog.profiling.controller;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.util.TempLocationManager;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Supplier;

public final class EnvironmentChecker {
  private static void appendLine(String line, StringBuilder sb) {
    sb.append(line).append(System.lineSeparator());
  }

  private static void appendLine(Supplier<StringBuilder> sbSupplier) {
    sbSupplier.get().append(System.lineSeparator());
  }

  /** Checks the current application's profiling environment using the profiler's own loader. */
  public static boolean checkEnvironment(StringBuilder sb) {
    if (!checkJvm(sb)) {
      return false;
    }

    boolean result = checkProfilerTempDir(sb);
    result &= checkProfilerLibrary(sb);
    appendLine(result ? "Profiler is ready to be used." : "Profiler environment check failed.", sb);
    return result;
  }

  @SuppressForbidden
  private static boolean checkJvm(StringBuilder sb) {
    if (!JavaVirtualMachine.isJavaVersionAtLeast(8)) {
      appendLine("Profiler requires Java 8 or newer", sb);
      return false;
    }
    appendLine(
        () ->
            sb.append("Using Java version: ")
                .append(JavaVirtualMachine.getRuntimeVersion())
                .append(" (")
                .append(SystemProperties.getOrDefault("java.home", "unknown"))
                .append(")"));
    appendLine(
        () ->
            sb.append("Running as user: ")
                .append(SystemProperties.getOrDefault("user.name", "unknown")));
    boolean result = false;
    result |= checkJFR(sb);
    result |= checkDdprof(sb);
    if (!result) {
      appendLine("Profiler is not supported on this JVM.", sb);
      return false;
    } else {
      appendLine("Profiler is supported on this JVM.", sb);
    }
    sb.append(System.lineSeparator());
    return true;
  }

  private static boolean checkProfilerTempDir(StringBuilder sb) {
    Path tempDir;
    try {
      tempDir = TempLocationManager.getInstance().getTempDir();
    } catch (Throwable t) {
      appendLine("Unable to obtain profiler temporary directory.", sb);
      appendStackTrace(t, sb);
      return false;
    }

    appendLine(() -> sb.append("Checking profiler temporary directory: ").append(tempDir));
    try {
      Files.delete(Files.createTempFile(tempDir, "env-check", ".tmp"));
      appendLine("Profiler temporary directory is writable", sb);
      return true;
    } catch (Throwable t) {
      appendLine(() -> sb.append("Unable to create test file in ").append(tempDir));
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        appendLine(() -> sb.append("Permissions: ").append(getPermissionsStringSafe(tempDir)));
      }
      appendStackTrace(t, sb);
      return false;
    }
  }

  private static boolean checkProfilerLibrary(StringBuilder sb) {
    if (!OperatingSystem.isLinux()) {
      appendLine("Skipping native library check on non-linux platform", sb);
      return true;
    }

    DdprofLibraryLoader.LibraryCheck check;
    try {
      check = DdprofLibraryLoader.checkLibrary();
    } catch (Throwable t) {
      appendLine("Unable to load native library using the profiling library loader.", sb);
      appendStackTrace(t, sb);
      return false;
    }

    if (check.loaded) {
      appendLine("Native library loaded successfully", sb);
      return true;
    }
    if (!check.earlierAttempt) {
      appendLine("Unable to load native library using the profiling library loader.", sb);
    } else if (check.reasonNotLoaded != null) {
      appendLine("Datadog profiler failed to load earlier in this JVM:", sb);
    } else {
      appendLine(
          "An earlier native library load failed. The loader does not retry within this JVM. "
              + "Check application startup logs for the original error; restart after addressing the cause.",
          sb);
    }
    appendStackTrace(check.reasonNotLoaded, sb);
    return false;
  }

  private static void appendStackTrace(Throwable t, StringBuilder sb) {
    if (t != null) {
      StringWriter stackTrace = new StringWriter();
      t.printStackTrace(new PrintWriter(stackTrace));
      appendLine(stackTrace.toString(), sb);
    }
  }

  @SuppressForbidden
  private static boolean checkJFR(StringBuilder sb) {
    if (JavaVirtualMachine.isOracleJDK8()) {
      appendLine(
          "JFR is commercial feature in Oracle JDK 8. Make sure you have the right license.", sb);
      return true;
    } else if (JavaVirtualMachine.isJ9()) {
      appendLine("JFR is not supported on J9 JVM.", sb);
      return false;
    } else {
      appendLine(
          () -> sb.append("JFR is supported on ").append(JavaVirtualMachine.getRuntimeVersion()));
      return true;
    }
  }

  @SuppressForbidden
  private static boolean checkDdprof(StringBuilder sb) {
    if (!OperatingSystem.isLinux()) {
      appendLine("Datadog profiler is only supported on Linux.", sb);
      return false;
    } else {
      appendLine(
          () ->
              sb.append("Datadog profiler is supported on ")
                  .append(JavaVirtualMachine.getRuntimeVersion()));
      return true;
    }
  }

  private static String getPermissionsStringSafe(Path file) {
    try {
      return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    } catch (IOException ignored) {
      return "<unavailable>";
    }
  }
}
