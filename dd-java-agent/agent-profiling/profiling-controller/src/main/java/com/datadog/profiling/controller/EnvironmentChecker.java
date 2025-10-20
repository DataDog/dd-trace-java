package com.datadog.profiling.controller;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import datadog.nativeloader.LibFile;
import datadog.nativeloader.NativeLoader;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.function.Supplier;

public final class EnvironmentChecker {
  // Loader for native libraries. This should eventually be consolidated into a single shared
  // instance, but for now, each module maintains its own instance to simplify the migration.
  private static final URL JAR_URL =
      EnvironmentChecker.class.getProtectionDomain().getCodeSource().getLocation();
  private static final NativeLoader NATIVE_LOADER =
      NativeLoader.builder()
          .nestedLayout()
          .fromClassLoader(new URLClassLoader(new URL[] {JAR_URL}))
          .build();

  private static void appendLine(String line, StringBuilder sb) {
    sb.append(line).append(System.lineSeparator());
  }

  private static void appendLine(Supplier<StringBuilder> sbSupplier) {
    sbSupplier.get().append(System.lineSeparator());
  }

  @SuppressForbidden
  public static boolean checkEnvironment(String temp, StringBuilder sb) {
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
    if (!checkTempLocation(temp, sb)) {
      appendLine("Profiler will not work properly due to issues with temp directory location.", sb);
      return false;
    } else {
      if (!temp.equals(SystemProperties.get("java.io.tmpdir"))) {
        appendLine(
            () ->
                sb.append("! Make sure to add '-Ddd.profiling.tempdir=")
                    .append(temp)
                    .append("' to your JVM command line !"));
      }
    }
    appendLine("Profiler is ready to be used.", sb);
    return true;
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

  @SuppressForbidden
  private static boolean checkTempLocation(String temp, StringBuilder sb) {
    // Check if the temp directory is writable
    if (temp == null || temp.isEmpty()) {
      appendLine("Temp directory is not specified.", sb);
      return false;
    }

    appendLine(() -> sb.append("Checking temporary directory: ").append(temp));

    Path base = Paths.get(temp);
    if (!Files.exists(base)) {
      appendLine(() -> sb.append("Temporary directory does not exist: ").append(base));
      return false;
    }
    Path target = base.resolve("dd-profiler").normalize();
    boolean rslt = true;
    Set<String> supportedViews = FileSystems.getDefault().supportedFileAttributeViews();
    boolean isPosix = supportedViews.contains("posix");
    try {
      if (isPosix) {
        Files.createDirectories(
            target,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } else {
        // non-posix, eg. Windows - let's rely on the created folders being world-writable
        Files.createDirectories(target);
      }
      appendLine(() -> sb.append("Temporary directory is writable: ").append(target));
      rslt &= checkCreateTempFile(target, sb);
      rslt &= checkLoadLibrary(target, sb);
    } catch (Exception e) {
      appendLine(() -> sb.append("Unable to create temp directory in location ").append(temp));
      if (isPosix) {
        appendLine(
            () ->
                sb.append("Base dir: ")
                    .append(base)
                    .append(" [")
                    .append(getPermissionsStringSafe(base))
                    .append("]"));
      }
      appendLine(() -> sb.append("Error: ").append(e));
    } finally {
      if (Files.exists(target)) {
        try {
          Files.walkFileTree(
              target,
              new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  Files.delete(file);
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                  Files.delete(dir);
                  return FileVisitResult.CONTINUE;
                }
              });
        } catch (IOException ignored) {
          // should never happen
        }
      }
    }
    return rslt;
  }

  private static String getPermissionsStringSafe(Path file) {
    try {
      return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    } catch (IOException ignored) {
      return "<unavailable>";
    }
  }

  @SuppressForbidden
  private static boolean checkCreateTempFile(Path target, StringBuilder sb) {
    // create a file to check if the directory is writable
    try {
      appendLine(() -> sb.append("Attempting to create a test file in: ").append(target));
      Path testFile = target.resolve("testfile");
      Files.createFile(testFile);
      appendLine(() -> sb.append("Test file created: ").append(testFile));
      return true;
    } catch (Exception e) {
      appendLine(() -> sb.append("Unable to create test file in temp directory ").append(target));
      appendLine(() -> sb.append("Error: ").append(e));
    }
    return false;
  }

  @SuppressForbidden
  private static boolean checkLoadLibrary(Path target, StringBuilder sb) {
    if (!OperatingSystem.isLinux()) {
      // we are loading the native library only on linux
      appendLine("Skipping native library check on non-linux platform", sb);
      return true;
    }
    appendLine(
        () -> sb.append("Attempting to load native library from: ").append("libjavaProfiler.so"));
    try (LibFile lib = NATIVE_LOADER.resolveDynamic("libjavaProfiler.so")) {
      lib.load();
      appendLine("Native library loaded successfully", sb);
      return true;
    } catch (Throwable t) {
      appendLine(
          () -> sb.append("Unable to load native library in temp directory ").append(target));
      appendLine(() -> sb.append("Error: ").append(t));
      return false;
    }
  }
}
